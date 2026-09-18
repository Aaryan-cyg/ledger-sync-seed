package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.DocumentLedgerStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentStoreTest {

    @TempDir
    Path tempDir;

    private SqlLedgerStore sqlStore;
    private DocumentLedgerStore docStore;

    @BeforeEach
    void setUp() throws IOException {
        Path dbPath = tempDir.resolve("test_ledger");
        sqlStore = new SqlLedgerStore(dbPath);
        sqlStore.migrate(Path.of("db", "migration"));
        docStore = new DocumentLedgerStore();
    }

    @AfterEach
    void tearDown() {
        if (sqlStore != null) {
            sqlStore.close();
        }
    }

    @Test
    void testBackfillAndConsistencyChecker() throws IOException {
        // Ingest corpus into SQL store
        IngestService ingest = new IngestService(new Parsers(), sqlStore);
        ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));
        assertTrue(sqlStore.count() > 0);

        // Run Backfill
        Backfill backfill = new Backfill(sqlStore, docStore);
        Backfill.Result res1 = backfill.run();
        assertTrue(res1.written() > 0);
        assertEquals(sqlStore.count(), res1.read());

        // Test Backfill idempotency: running a second time writes 0, skips all
        Backfill.Result res2 = backfill.run();
        assertEquals(0, res2.written());
        assertEquals(res1.written(), res2.skipped());

        // ConsistencyChecker proves both stores agree completely
        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();
        assertEquals(0, divergences.size(), "Stores must agree after backfill");

        // Now deliberately alter the document store and verify ConsistencyChecker catches it!
        // 1. Alter an amount
        YearMonth ym = YearMonth.of(2026, 7);
        List<NormalizedTxn> txns = docStore.forAccountMonth("4821", ym);
        assertFalse(txns.isEmpty());
        NormalizedTxn original = txns.get(0);
        NormalizedTxn alteredAmount = new NormalizedTxn(original.accountLast4(), original.occurredAt(),
                original.direction(), original.amount().add(new BigDecimal("100.00")),
                original.category(), original.merchant(), original.sourceMessageIds());
        docStore.save(alteredAmount);

        List<ConsistencyChecker.Divergence> div1 = checker.check();
        assertTrue(div1.size() > 0, "Checker must detect altered amount");
        assertTrue(div1.stream().anyMatch(d -> d.what().contains("amount")));

        // Restore and alter category
        docStore.save(original);
        NormalizedTxn alteredCat = new NormalizedTxn(original.accountLast4(), original.occurredAt(),
                original.direction(), original.amount(),
                original.category() == Category.SPEND ? Category.MICRO : Category.SPEND,
                original.merchant(), original.sourceMessageIds());
        docStore.save(alteredCat);

        List<ConsistencyChecker.Divergence> div2 = checker.check();
        assertTrue(div2.size() > 0, "Checker must detect altered category");
        assertTrue(div2.stream().anyMatch(d -> d.what().contains("category")));
    }

    @Test
    void testThreeAccessPatternsAtScale100k() {
        DocumentLedgerStore store = new DocumentLedgerStore();
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-07-01T00:00:00+05:30");

        // Generate 100,000 transactions across 10 accounts and 10 months (10,000 per month)
        // For account "4821" in July 2026, there are exactly 1,000 transactions.
        System.out.println("Generating 100,000 transactions for benchmark...");
        for (int i = 0; i < 100_000; i++) {
            String acct = String.format("%04d", (i % 10));
            // Distribute across 10 months (Jan 2026 to Oct 2026)
            int monthOffset = (i / 10) % 10;
            OffsetDateTime when = baseTime.plusMonths(monthOffset).plusSeconds(i % 86400);
            Direction dir = (i % 3 == 0) ? Direction.CREDIT : Direction.DEBIT;
            Category cat = (dir == Direction.CREDIT) ? Category.INCOME : (((i / 10) % 2 == 0) ? Category.SPEND : Category.MICRO);
            BigDecimal amt = new BigDecimal("50.00").add(new BigDecimal(i % 500)).setScale(2);
            String msgId = "msg-" + i;

            store.save(new NormalizedTxn(acct, when, dir, amt, cat, "MERCHANT-" + (i % 50), List.of(msgId)));
        }
        assertEquals(100_000, store.totalDocuments());

        // Query 1: One account's transactions for one month, newest first
        YearMonth q1Month = YearMonth.of(2026, 7);
        List<NormalizedTxn> q1Result = store.forAccountMonth("0001", q1Month);
        DocumentLedgerStore.QueryMetrics q1Metrics = store.getLastQ1Metrics();
        assertEquals(q1Result.size(), q1Metrics.examined());
        assertEquals(q1Result.size(), q1Metrics.returned());
        assertEquals(1000, q1Result.size());
        // Verify newest first ordering
        for (int i = 1; i < q1Result.size(); i++) {
            assertTrue(!q1Result.get(i).occurredAt().isAfter(q1Result.get(i - 1).occurredAt()));
        }

        // Query 2: Running totals per category for an account
        Map<Category, BigDecimal> q2Result = store.categoryTotals("0001");
        DocumentLedgerStore.QueryMetrics q2Metrics = store.getLastQ2Metrics();
        assertEquals(1, q2Metrics.examined());
        assertEquals(1, q2Metrics.returned());
        assertTrue(q2Result.get(Category.SPEND).compareTo(BigDecimal.ZERO) > 0);

        // Query 3: Given a message ID, which transaction did it produce?
        Optional<NormalizedTxn> q3Result = store.byMessageId("msg-4242");
        DocumentLedgerStore.QueryMetrics q3Metrics = store.getLastQ3Metrics();
        assertTrue(q3Result.isPresent());
        assertEquals("msg-4242", q3Result.get().sourceMessageIds().get(0));
        assertEquals(1, q3Metrics.examined());
        assertEquals(1, q3Metrics.returned());

        System.out.println("=== 100,000 TRANSACTIONS BENCHMARK RESULTS ===");
        System.out.printf("Query 1 (forAccountMonth): totalDocsExamined = %d, nReturned = %d%n",
                q1Metrics.examined(), q1Metrics.returned());
        System.out.printf("Query 2 (categoryTotals):  totalDocsExamined = %d, nReturned = %d%n",
                q2Metrics.examined(), q2Metrics.returned());
        System.out.printf("Query 3 (byMessageId):     totalDocsExamined = %d, nReturned = %d%n",
                q3Metrics.examined(), q3Metrics.returned());
    }
}
