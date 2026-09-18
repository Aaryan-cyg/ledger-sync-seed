package in.simplifymoney.ledgersync.report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores stated bank balance checkpoints extracted from messages to verify
 * account continuity and produce reconciliation reports.
 */
public final class BalanceCheckpoints {

    public record Checkpoint(String accountLast4, OffsetDateTime occurredAt, BigDecimal balance, String messageId) {}

    private static final Map<String, List<Checkpoint>> IN_MEMORY = new ConcurrentHashMap<>();

    private BalanceCheckpoints() {}

    public static void record(String accountLast4, OffsetDateTime occurredAt, BigDecimal balance, String messageId) {
        if (accountLast4 == null || occurredAt == null || balance == null) return;
        IN_MEMORY.computeIfAbsent(accountLast4, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Checkpoint(accountLast4, occurredAt, balance, messageId));
    }

    public static List<Checkpoint> forAccount(String accountLast4) {
        List<Checkpoint> list = IN_MEMORY.get(accountLast4);
        if (list == null) return List.of();
        synchronized (list) {
            List<Checkpoint> copy = new ArrayList<>(list);
            copy.sort(Comparator.comparing(Checkpoint::occurredAt));
            return copy;
        }
    }

    public static void clear() {
        IN_MEMORY.clear();
    }
}
