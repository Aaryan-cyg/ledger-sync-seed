package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.BalanceCheckpoints;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages, deduplicates evidence messages into real
 * transactions, classifies each into its canonical category (SPEND, INCOME,
 * MICRO, TRANSFER), and persists idempotently into the ledger.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        List<ParsedTxn> parsedList = new ArrayList<>();
        int skipped = 0;

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn pt = p.get();
            parsedList.add(pt);
            if (pt.statedBalance() != null) {
                BalanceCheckpoints.record(pt.accountLast4(), pt.occurredAt(), pt.statedBalance(), pt.sourceMessageId());
                if (store instanceof in.simplifymoney.ledgersync.store.SqlLedgerStore sql) {
                    sql.saveCheckpoint(pt.accountLast4(), pt.occurredAt(), pt.statedBalance(), pt.sourceMessageId());
                }
            }
        }

        // Deduplicate parsed messages by natural key
        Map<TxnKey, GroupedTxn> groups = new LinkedHashMap<>();
        for (ParsedTxn pt : parsedList) {
            TxnKey key = new TxnKey(pt.accountLast4(), pt.occurredAt(), pt.direction(), pt.amount());
            GroupedTxn group = groups.get(key);
            if (group == null) {
                group = new GroupedTxn(pt);
                groups.put(key, group);
            } else {
                group.addSource(pt);
            }
        }

        List<GroupedTxn> txnList = new ArrayList<>(groups.values());

        // Transfer detection between accounts
        Set<Integer> transferIndices = new HashSet<>();
        for (int i = 0; i < txnList.size(); i++) {
            GroupedTxn t1 = txnList.get(i);
            if (t1.direction != Direction.DEBIT) continue;
            for (int j = 0; j < txnList.size(); j++) {
                if (i == j) continue;
                GroupedTxn t2 = txnList.get(j);
                if (t2.direction != Direction.CREDIT) continue;
                if (t1.accountLast4.equals(t2.accountLast4)) continue;
                if (t1.amount.compareTo(t2.amount) == 0) {
                    long diffMinutes = Math.abs(Duration.between(t1.occurredAt, t2.occurredAt).toMinutes());
                    if (diffMinutes <= 15) {
                        transferIndices.add(i);
                        transferIndices.add(j);
                    }
                }
            }
        }

        // Build NormalizedTxn list with categories
        List<NormalizedTxn> normalized = new ArrayList<>();
        for (int i = 0; i < txnList.size(); i++) {
            GroupedTxn g = txnList.get(i);
            Category cat;
            if (transferIndices.contains(i)) {
                cat = Category.TRANSFER;
            } else if (g.direction == Direction.DEBIT) {
                if (g.amount.compareTo(new BigDecimal("100.00")) <= 0 && isUpi(g.merchant)) {
                    cat = Category.MICRO;
                } else {
                    cat = Category.SPEND;
                }
            } else {
                cat = Category.INCOME;
            }

            List<String> sortedSources = g.sourceMessageIds.stream().distinct().sorted().toList();
            normalized.add(new NormalizedTxn(g.accountLast4, g.occurredAt, g.direction,
                    g.amount, cat, g.merchant, sortedSources));
        }

        // Idempotent save to store
        Set<TxnKey> existingKeys = new HashSet<>();
        for (NormalizedTxn existing : store.all()) {
            existingKeys.add(new TxnKey(existing.accountLast4(), existing.occurredAt(),
                    existing.direction(), existing.amount()));
        }

        int written = 0;
        for (NormalizedTxn txn : normalized) {
            TxnKey key = new TxnKey(txn.accountLast4(), txn.occurredAt(), txn.direction(), txn.amount());
            if (!existingKeys.contains(key)) {
                store.save(txn);
                existingKeys.add(key);
                written++;
            }
        }

        return new Stats(messages.size(), written, skipped);
    }

    private static boolean isUpi(String merchant) {
        if (merchant == null) return false;
        return merchant.toUpperCase().contains("UPI");
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}

    private record TxnKey(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {}

    private static final class GroupedTxn {
        final String accountLast4;
        final OffsetDateTime occurredAt;
        final Direction direction;
        final BigDecimal amount;
        String merchant;
        final List<String> sourceMessageIds = new ArrayList<>();

        GroupedTxn(ParsedTxn pt) {
            this.accountLast4 = pt.accountLast4();
            this.occurredAt = pt.occurredAt();
            this.direction = pt.direction();
            this.amount = pt.amount();
            this.merchant = pt.merchant();
            this.sourceMessageIds.add(pt.sourceMessageId());
        }

        void addSource(ParsedTxn pt) {
            this.sourceMessageIds.add(pt.sourceMessageId());
            if (this.merchant == null || this.merchant.isBlank()) {
                this.merchant = pt.merchant();
            } else if (pt.merchant() != null && pt.merchant().length() > this.merchant.length()) {
                this.merchant = pt.merchant();
            }
        }
    }
}
