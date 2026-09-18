package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production document store implementing DocumentStore.
 *
 * Documents are modeled around the three core query access patterns:
 *  1. Monthly transactions: compound index (accountLast4, yearMonth, occurredAt DESC)
 *  2. Running category totals: pre-aggregated account document updated on save
 *  3. Message-to-transaction lookup: multikey inverted index on sourceMessageIds
 */
public final class DocumentLedgerStore implements DocumentStore {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    // Primary store: document id -> NormalizedTxn
    private final Map<String, NormalizedTxn> documents = new ConcurrentHashMap<>();

    // Index 1: (accountLast4, YearMonth) -> sorted list of txns (newest first)
    private final Map<String, List<NormalizedTxn>> accountMonthIndex = new ConcurrentHashMap<>();

    // Index 2: accountLast4 -> Map of Category -> Running Total
    private final Map<String, Map<Category, BigDecimal>> runningTotals = new ConcurrentHashMap<>();

    // Index 3: multikey index from sourceMessageId -> NormalizedTxn
    private final Map<String, NormalizedTxn> messageIndex = new ConcurrentHashMap<>();

    // Query examination metrics
    public record QueryMetrics(long examined, long returned) {}

    private QueryMetrics lastQ1Metrics = new QueryMetrics(0, 0);
    private QueryMetrics lastQ2Metrics = new QueryMetrics(0, 0);
    private QueryMetrics lastQ3Metrics = new QueryMetrics(0, 0);

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String docId = docIdOf(txn);
        NormalizedTxn existing = documents.put(docId, txn);

        // Update Index 1: account + month
        String amKey = accountMonthKey(txn.accountLast4(), YearMonth.from(txn.occurredAt()));
        List<NormalizedTxn> monthList = accountMonthIndex.computeIfAbsent(amKey, k -> new ArrayList<>());
        if (existing != null) {
            monthList.removeIf(t -> docIdOf(t).equals(docId));
        }
        monthList.add(txn);
        monthList.sort(Comparator.comparing(NormalizedTxn::occurredAt).reversed());

        // Update Index 2: running category totals for account
        Map<Category, BigDecimal> totals = runningTotals.computeIfAbsent(txn.accountLast4(), k -> {
            Map<Category, BigDecimal> init = new LinkedHashMap<>();
            for (Category c : Category.values()) init.put(c, ZERO);
            return init;
        });
        if (existing != null) {
            totals.put(existing.category(), totals.get(existing.category()).subtract(existing.amount()));
        }
        totals.put(txn.category(), totals.get(txn.category()).add(txn.amount()));

        // Update Index 3: multikey index for each message ID
        for (String msgId : txn.sourceMessageIds()) {
            messageIndex.put(msgId, txn);
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        String key = accountMonthKey(accountLast4, month);
        List<NormalizedTxn> list = accountMonthIndex.get(key);
        if (list == null || list.isEmpty()) {
            lastQ1Metrics = new QueryMetrics(0, 0);
            return List.of();
        }
        // Direct index hit: examined equals returned
        lastQ1Metrics = new QueryMetrics(list.size(), list.size());
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = runningTotals.get(accountLast4);
        if (totals == null) {
            Map<Category, BigDecimal> empty = new LinkedHashMap<>();
            for (Category c : Category.values()) empty.put(c, ZERO);
            lastQ2Metrics = new QueryMetrics(0, 0);
            return empty;
        }
        // Direct point lookup on account summary document: examined = 1, returned = 1
        lastQ2Metrics = new QueryMetrics(1, 1);
        Map<Category, BigDecimal> copy = new LinkedHashMap<>();
        totals.forEach((k, v) -> copy.put(k, v.setScale(2)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        NormalizedTxn txn = messageIndex.get(messageId);
        if (txn == null) {
            lastQ3Metrics = new QueryMetrics(0, 0);
            return Optional.empty();
        }
        // Point lookup via multikey index: examined = 1, returned = 1
        lastQ3Metrics = new QueryMetrics(1, 1);
        return Optional.of(txn);
    }

    public QueryMetrics getLastQ1Metrics() { return lastQ1Metrics; }
    public QueryMetrics getLastQ2Metrics() { return lastQ2Metrics; }
    public QueryMetrics getLastQ3Metrics() { return lastQ3Metrics; }

    public int totalDocuments() { return documents.size(); }

    public void saveToFile(Path file) throws IOException {
        List<Object> list = new ArrayList<>();
        for (NormalizedTxn t : documents.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("accountLast4", t.accountLast4());
            map.put("occurredAt", t.occurredAt().toString());
            map.put("direction", t.direction().name());
            map.put("amount", t.amount().toPlainString());
            map.put("category", t.category().name());
            map.put("merchant", t.merchant());
            map.put("sourceMessageIds", t.sourceMessageIds());
            list.add(map);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.writePretty(list));
    }

    @SuppressWarnings("unchecked")
    public void loadFromFile(Path file) throws IOException {
        if (!Files.exists(file)) return;
        Object parsed = Json.parse(Files.readString(file));
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> map = (Map<String, Object>) m;
                    String acct = (String) map.get("accountLast4");
                    OffsetDateTime when = OffsetDateTime.parse((String) map.get("occurredAt"));
                    Direction dir = Direction.valueOf((String) map.get("direction"));
                    BigDecimal amt = map.get("amount") instanceof BigDecimal bd
                            ? bd.setScale(2)
                            : new BigDecimal((String) map.get("amount")).setScale(2);
                    Category cat = Category.valueOf((String) map.get("category"));
                    String merchant = (String) map.get("merchant");
                    List<String> srcIds = (List<String>) map.get("sourceMessageIds");
                    save(new NormalizedTxn(acct, when, dir, amt, cat, merchant, srcIds));
                }
            }
        }
    }

    private static String docIdOf(NormalizedTxn t) {
        return t.accountLast4() + "_" + t.occurredAt() + "_" + t.direction() + "_" + t.amount().toPlainString();
    }

    private static String accountMonthKey(String acct, YearMonth ym) {
        return acct + "_" + ym.toString();
    }
}
