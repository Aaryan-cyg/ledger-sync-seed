package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves transactions from the legacy SQL store into the document store.
 *
 * Designed to handle:
 *  1. Dirty legacy SQL store with duplicate rows and missing uniqueness guarantees.
 *  2. Multiple executions / restartability after partial failure.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> sqlRows = source.all();
        long read = sqlRows.size();

        // 1. Deduplicate legacy SQL rows by natural key: accountLast4, occurredAt, direction, amount
        Map<String, GroupedBackfill> groups = new LinkedHashMap<>();
        for (NormalizedTxn row : sqlRows) {
            String key = keyOf(row.accountLast4(), row.occurredAt(), row.direction(), row.amount());
            GroupedBackfill g = groups.get(key);
            if (g == null) {
                g = new GroupedBackfill(row);
                groups.put(key, g);
            } else {
                g.merge(row);
            }
        }

        List<GroupedBackfill> list = new ArrayList<>(groups.values());

        long written = 0;
        long skipped = 0;

        for (GroupedBackfill g : list) {
            NormalizedTxn cleanTxn = new NormalizedTxn(g.accountLast4, g.occurredAt, g.direction,
                    g.amount, g.category, g.merchant, g.sourceMessageIds.stream().distinct().sorted().toList());

            // Check if already in DocumentStore (idempotent, partial-failure safe)
            YearMonth ym = YearMonth.from(cleanTxn.occurredAt());
            List<NormalizedTxn> inDoc = target.forAccountMonth(cleanTxn.accountLast4(), ym);
            boolean exists = inDoc.stream().anyMatch(t ->
                    t.accountLast4().equals(cleanTxn.accountLast4())
                    && t.occurredAt().isEqual(cleanTxn.occurredAt())
                    && t.direction() == cleanTxn.direction()
                    && t.amount().compareTo(cleanTxn.amount()) == 0);

            if (exists) {
                skipped++;
            } else {
                target.save(cleanTxn);
                written++;
            }
        }

        return new Result(read, written, skipped);
    }

    private static String keyOf(String acct, OffsetDateTime when, Direction dir, BigDecimal amt) {
        return acct + "|" + when.toString() + "|" + dir.name() + "|" + amt.toPlainString();
    }

    public record Result(long read, long written, long skipped) {}

    private static final class GroupedBackfill {
        final String accountLast4;
        final OffsetDateTime occurredAt;
        final Direction direction;
        final BigDecimal amount;
        Category category;
        String merchant;
        final List<String> sourceMessageIds = new ArrayList<>();

        GroupedBackfill(NormalizedTxn t) {
            this.accountLast4 = t.accountLast4();
            this.occurredAt = t.occurredAt();
            this.direction = t.direction();
            this.amount = t.amount();
            this.category = t.category();
            this.merchant = t.merchant();
            this.sourceMessageIds.addAll(t.sourceMessageIds());
        }

        void merge(NormalizedTxn t) {
            this.sourceMessageIds.addAll(t.sourceMessageIds());
            if (this.merchant == null || this.merchant.isBlank()) {
                this.merchant = t.merchant();
            }
        }
    }
}
