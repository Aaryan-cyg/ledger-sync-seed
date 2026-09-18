package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 *
 * reconciliation() has not been written at all.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            int microCount = 0;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(t.amount());
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.setScale(2).toPlainString());
            a.put("income", income.setScale(2).toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.setScale(2).toPlainString());
            a.put("transferred_out", transferredOut.setScale(2).toPlainString());
            a.put("transferred_in", transferredIn.setScale(2).toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        List<Object> discrepancies = new java.util.ArrayList<>();
        for (String acct : new TreeSet<>(ledger.stream().map(NormalizedTxn::accountLast4).toList())) {
            List<BalanceCheckpoints.Checkpoint> cps = BalanceCheckpoints.forAccount(acct);
            if (cps.size() < 2) continue;

            List<NormalizedTxn> acctTxns = ledger.stream()
                    .filter(t -> t.accountLast4().equals(acct))
                    .sorted(java.util.Comparator.comparing(NormalizedTxn::occurredAt))
                    .toList();

            for (int i = 1; i < cps.size(); i++) {
                BalanceCheckpoints.Checkpoint prev = cps.get(i - 1);
                BalanceCheckpoints.Checkpoint curr = cps.get(i);
                if (prev.occurredAt().isEqual(curr.occurredAt())) continue;

                BigDecimal ledgerDelta = BigDecimal.ZERO;
                for (NormalizedTxn t : acctTxns) {
                    if (t.occurredAt().isAfter(prev.occurredAt()) && !t.occurredAt().isAfter(curr.occurredAt())) {
                        ledgerDelta = t.direction() == Direction.CREDIT
                                ? ledgerDelta.add(t.amount())
                                : ledgerDelta.subtract(t.amount());
                    }
                }

                BigDecimal actualDelta = curr.balance().subtract(prev.balance());
                BigDecimal diff = actualDelta.subtract(ledgerDelta);
                if (diff.abs().compareTo(new BigDecimal("0.01")) > 0) {
                    Map<String, Object> disc = new LinkedHashMap<>();
                    disc.put("account_last4", acct);
                    disc.put("occurred_at", curr.occurredAt().toString());
                    disc.put("amount", diff.abs().setScale(2).toPlainString());
                    disc.put("note", "Unaccounted balance divergence: balance changed by "
                            + actualDelta.setScale(2).toPlainString()
                            + " between " + prev.occurredAt() + " and " + curr.occurredAt()
                            + ", but ledger transactions account for " + ledgerDelta.setScale(2).toPlainString()
                            + "; unexplained difference of " + diff.abs().setScale(2).toPlainString());
                    discrepancies.add(disc);
                }
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
