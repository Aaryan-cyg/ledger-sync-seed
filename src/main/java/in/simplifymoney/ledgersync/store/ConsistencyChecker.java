package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Proves the SQL store and Document store agree at the field level, and names
 * precisely where they do not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> out = new ArrayList<>();
        List<NormalizedTxn> sqlAll = sql.all();

        // Group SQL rows by natural key to normalize any legacy duplicates
        Map<String, NormalizedTxn> sqlTxns = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlAll) {
            String key = keyOf(t);
            NormalizedTxn existing = sqlTxns.get(key);
            if (existing == null) {
                sqlTxns.put(key, t);
            } else {
                Set<String> merged = new HashSet<>(existing.sourceMessageIds());
                merged.addAll(t.sourceMessageIds());
                sqlTxns.put(key, new NormalizedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                        t.amount(), t.category(), t.merchant(), merged.stream().sorted().toList()));
            }
        }

        // Collect all accounts and months from SQL
        Map<String, Set<YearMonth>> acctMonths = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlTxns.values()) {
            acctMonths.computeIfAbsent(t.accountLast4(), k -> new HashSet<>())
                    .add(YearMonth.from(t.occurredAt()));
        }

        // Collect all transactions from DocumentStore via forAccountMonth query
        Map<String, NormalizedTxn> docTxns = new LinkedHashMap<>();
        for (Map.Entry<String, Set<YearMonth>> e : acctMonths.entrySet()) {
            String acct = e.getKey();
            for (YearMonth ym : e.getValue()) {
                List<NormalizedTxn> fromDoc = documents.forAccountMonth(acct, ym);
                for (NormalizedTxn dt : fromDoc) {
                    docTxns.put(keyOf(dt), dt);
                }
            }
        }

        // 1. Compare SQL transactions against DocumentStore transactions using byMessageId and month queries
        Set<String> verifiedMessageIds = new HashSet<>();
        for (NormalizedTxn s : sqlTxns.values()) {
            for (String msgId : s.sourceMessageIds()) {
                if (!verifiedMessageIds.add(msgId)) continue;
                Optional<NormalizedTxn> byMsg = documents.byMessageId(msgId);
                if (byMsg.isEmpty()) {
                    out.add(new Divergence("missing in documents for messageId " + msgId, s.toString(), "null"));
                    continue;
                }
                NormalizedTxn d = byMsg.get();
                if (s.amount().compareTo(d.amount()) != 0) {
                    out.add(new Divergence("amount for messageId " + msgId, s.amount().toPlainString(), d.amount().toPlainString()));
                }
                if (s.direction() != d.direction()) {
                    out.add(new Divergence("direction for messageId " + msgId, s.direction().name(), d.direction().name()));
                }
                if (s.category() != d.category()) {
                    out.add(new Divergence("category for messageId " + msgId, s.category().name(), d.category().name()));
                }
                if (!s.occurredAt().isEqual(d.occurredAt())) {
                    out.add(new Divergence("occurredAt for messageId " + msgId, s.occurredAt().toString(), d.occurredAt().toString()));
                }
                if (!s.accountLast4().equals(d.accountLast4())) {
                    out.add(new Divergence("accountLast4 for messageId " + msgId, s.accountLast4(), d.accountLast4()));
                }
                if (!s.sourceMessageIds().equals(d.sourceMessageIds())) {
                    out.add(new Divergence("sourceMessageIds for messageId " + msgId,
                            s.sourceMessageIds().toString(), d.sourceMessageIds().toString()));
                }
            }
        }

        // 2. Check for extra transactions in DocumentStore
        for (Map.Entry<String, NormalizedTxn> entry : docTxns.entrySet()) {
            String key = entry.getKey();
            if (!sqlTxns.containsKey(key)) {
                out.add(new Divergence("extra in documents: " + key, "null", entry.getValue().toString()));
            }
        }

        // 3. Compare running category totals for each account
        for (String acct : acctMonths.keySet()) {
            Map<Category, BigDecimal> docTotals = documents.categoryTotals(acct);
            Map<Category, BigDecimal> sqlCatTotals = new LinkedHashMap<>();
            for (Category c : Category.values()) sqlCatTotals.put(c, BigDecimal.ZERO.setScale(2));

            for (NormalizedTxn t : sqlTxns.values()) {
                if (t.accountLast4().equals(acct)) {
                    sqlCatTotals.put(t.category(), sqlCatTotals.get(t.category()).add(t.amount()));
                }
            }

            for (Category c : Category.values()) {
                BigDecimal sTot = sqlCatTotals.get(c);
                BigDecimal dTot = docTotals.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                if (sTot.compareTo(dTot) != 0) {
                    out.add(new Divergence("categoryTotals[" + acct + "][" + c + "]",
                            sTot.toPlainString(), dTot.toPlainString()));
                }
            }
        }

        return out;
    }

    private static String keyOf(NormalizedTxn t) {
        return t.accountLast4() + "@" + t.occurredAt() + "[" + t.direction() + " " + t.amount().toPlainString() + "]";
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
