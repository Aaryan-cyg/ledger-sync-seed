package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MongoDocumentStoreTest {

    private static final String TEST_DB = "ledgersync_test";
    private MongoDocumentStore mongoStore;
    private SqlLedgerStore sqlStore;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        try {
            mongoStore = new MongoDocumentStore(MongoDocumentStore.resolveUri(), TEST_DB);
            mongoStore.clear();
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort(
                    "MongoDB is not running on " + MongoDocumentStore.resolveUri()
                            + ". Start it via 'docker compose up -d' or local mongod. Error: " + e.getMessage());
        }

        Path dbPath = tempDir.resolve("sql_test_ledger");
        sqlStore = new SqlLedgerStore(dbPath);
        sqlStore.migrate(Path.of("db", "migration"));
    }

    @AfterEach
    void tearDown() {
        if (mongoStore != null) {
            mongoStore.clear();
            mongoStore.close();
        }
        if (sqlStore != null) {
            sqlStore.close();
        }
    }

    @Test
    void testSaveReadAndIdempotency() {
        OffsetDateTime dt = OffsetDateTime.parse("2026-07-15T14:30:00+05:30");
        NormalizedTxn txn1 = new NormalizedTxn("4821", dt, Direction.DEBIT,
                new BigDecimal("499.50"), Category.SPEND, "SWIGGY", List.of("m-001"));

        mongoStore.save(txn1);
        assertEquals(1, mongoStore.totalDocuments());

        Optional<NormalizedTxn> read = mongoStore.byMessageId("m-001");
        assertTrue(read.isPresent());
        assertEquals("4821", read.get().accountLast4());
        assertEquals(new BigDecimal("499.50"), read.get().amount());
        assertEquals(Category.SPEND, read.get().category());

        // Save second message for same transaction -> merges sourceMessageIds without duplicate doc
        NormalizedTxn txn2 = new NormalizedTxn("4821", dt, Direction.DEBIT,
                new BigDecimal("499.50"), Category.SPEND, "SWIGGY", List.of("m-002"));
        mongoStore.save(txn2);
        assertEquals(1, mongoStore.totalDocuments());

        Optional<NormalizedTxn> readByNewId = mongoStore.byMessageId("m-002");
        assertTrue(readByNewId.isPresent());
        assertEquals(List.of("m-001", "m-002"), readByNewId.get().sourceMessageIds());
    }

    @Test
    void testQ1MonthlyQueryNewestFirst() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-02T10:00:00+05:30");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-07-20T18:00:00+05:30");
        OffsetDateTime t3 = OffsetDateTime.parse("2026-08-01T09:00:00+05:30"); // Different month

        mongoStore.save(new NormalizedTxn("4821", t1, Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "M1", List.of("msg-1")));
        mongoStore.save(new NormalizedTxn("4821", t2, Direction.DEBIT, new BigDecimal("200.00"), Category.SPEND, "M2", List.of("msg-2")));
        mongoStore.save(new NormalizedTxn("4821", t3, Direction.DEBIT, new BigDecimal("300.00"), Category.SPEND, "M3", List.of("msg-3")));
        mongoStore.save(new NormalizedTxn("9075", t1, Direction.DEBIT, new BigDecimal("400.00"), Category.SPEND, "M4", List.of("msg-4")));

        List<NormalizedTxn> july4821 = mongoStore.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(2, july4821.size());
        assertEquals("msg-2", july4821.get(0).sourceMessageIds().get(0)); // t2 is newer
        assertEquals("msg-1", july4821.get(1).sourceMessageIds().get(0));
    }

    @Test
    void testQ2CategoryTotals() {
        OffsetDateTime t = OffsetDateTime.parse("2026-07-05T12:00:00+05:30");
        mongoStore.save(new NormalizedTxn("4821", t, Direction.DEBIT, new BigDecimal("1500.00"), Category.SPEND, "AMAZON", List.of("m-1")));
        mongoStore.save(new NormalizedTxn("4821", t.plusHours(1), Direction.CREDIT, new BigDecimal("50000.00"), Category.INCOME, "SALARY", List.of("m-2")));
        mongoStore.save(new NormalizedTxn("4821", t.plusHours(2), Direction.DEBIT, new BigDecimal("45.00"), Category.MICRO, "CHAI", List.of("m-3")));
        mongoStore.save(new NormalizedTxn("4821", t.plusHours(3), Direction.DEBIT, new BigDecimal("10000.00"), Category.TRANSFER, "TRANSFER", List.of("m-4")));

        Map<Category, BigDecimal> totals = mongoStore.categoryTotals("4821");
        assertEquals(new BigDecimal("1500.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("50000.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("45.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("10000.00"), totals.get(Category.TRANSFER));
    }

    @Test
    void testQ3MessageIdLookup() {
        OffsetDateTime t = OffsetDateTime.parse("2026-07-10T12:00:00+05:30");
        mongoStore.save(new NormalizedTxn("9075", t, Direction.DEBIT, new BigDecimal("1250.00"), Category.SPEND, "STORE", List.of("sms-abc", "email-xyz")));

        Optional<NormalizedTxn> bySms = mongoStore.byMessageId("sms-abc");
        Optional<NormalizedTxn> byEmail = mongoStore.byMessageId("email-xyz");
        Optional<NormalizedTxn> nonExistent = mongoStore.byMessageId("fake-id");

        assertTrue(bySms.isPresent());
        assertTrue(byEmail.isPresent());
        assertTrue(nonExistent.isEmpty());
        assertEquals(bySms.get().amount(), byEmail.get().amount());
    }

    @Test
    void testBackfillAndConsistencyCheckerWithMongo() throws IOException {
        IngestService ingest = new IngestService(new Parsers(), sqlStore);
        ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));
        assertTrue(sqlStore.count() > 0);

        // Run Backfill into MongoDB
        Backfill backfill = new Backfill(sqlStore, mongoStore);
        Backfill.Result res1 = backfill.run();
        assertTrue(res1.written() > 0);
        assertEquals(sqlStore.count(), res1.read());

        // Backfill idempotency: second run writes 0, skips all
        Backfill.Result res2 = backfill.run();
        assertEquals(0, res2.written());
        assertEquals(res1.written(), res2.skipped());

        // ConsistencyChecker proves SQL and Mongo agree completely
        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, mongoStore);
        List<ConsistencyChecker.Divergence> divs = checker.check();
        assertEquals(0, divs.size(), "SQL store and MongoDB must agree after backfill");

        // Deliberately alter document in MongoDB and verify ConsistencyChecker detects it
        YearMonth ym = YearMonth.of(2026, 7);
        List<NormalizedTxn> txns = mongoStore.forAccountMonth("4821", ym);
        assertFalse(txns.isEmpty());
        NormalizedTxn original = txns.get(0);

        // 1. Alter amount in MongoDB directly
        String targetMsgId = original.sourceMessageIds().get(0);
        BigDecimal newAmount = original.amount().add(new BigDecimal("100.00")).setScale(2);
        com.mongodb.client.MongoDatabase db = com.mongodb.client.MongoClients.create(MongoDocumentStore.resolveUri()).getDatabase(TEST_DB);
        db.getCollection("transactions").updateOne(
                com.mongodb.client.model.Filters.eq("sourceMessageIds", targetMsgId),
                com.mongodb.client.model.Updates.set("amount", new org.bson.types.Decimal128(newAmount))
        );

        List<ConsistencyChecker.Divergence> div1 = checker.check();
        assertTrue(div1.size() > 0, "Checker must detect altered amount in Mongo");
        assertTrue(div1.stream().anyMatch(d -> d.what().contains("amount")), "Divergence must name amount");

        // Restore original amount and alter category
        db.getCollection("transactions").updateOne(
                com.mongodb.client.model.Filters.eq("sourceMessageIds", targetMsgId),
                com.mongodb.client.model.Updates.set("amount", new org.bson.types.Decimal128(original.amount().setScale(2)))
        );
        Category alteredCategory = original.category() == Category.SPEND ? Category.MICRO : Category.SPEND;
        db.getCollection("transactions").updateOne(
                com.mongodb.client.model.Filters.eq("sourceMessageIds", targetMsgId),
                com.mongodb.client.model.Updates.set("category", alteredCategory.name())
        );

        List<ConsistencyChecker.Divergence> div2 = checker.check();
        assertTrue(div2.size() > 0, "Checker must detect altered category in Mongo");
        assertTrue(div2.stream().anyMatch(d -> d.what().contains("category")), "Divergence must name category");
    }

    @Test
    void test100kBenchmarkAgainstMongo() {
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-07-01T00:00:00+05:30");
        System.out.println("Generating 100,000 transactions into MongoDB for benchmark...");

        List<NormalizedTxn> batch = new ArrayList<>(5000);
        for (int i = 0; i < 100_000; i++) {
            String acct = String.format("%04d", (i % 10));
            int monthOffset = (i / 10) % 10;
            OffsetDateTime when = baseTime.plusMonths(monthOffset).plusSeconds(i % 86400);
            Direction dir = (i % 3 == 0) ? Direction.CREDIT : Direction.DEBIT;
            Category cat = (dir == Direction.CREDIT) ? Category.INCOME : (((i / 10) % 2 == 0) ? Category.SPEND : Category.MICRO);
            BigDecimal amt = new BigDecimal("50.00").add(new BigDecimal(i % 500)).setScale(2);
            String msgId = "mongo-msg-" + i;

            batch.add(new NormalizedTxn(acct, when, dir, amt, cat, "MERCHANT-" + (i % 50), List.of(msgId)));
            if (batch.size() == 5000) {
                mongoStore.saveBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mongoStore.saveBatch(batch);
            batch.clear();
        }

        assertEquals(100_000, mongoStore.totalDocuments());

        // Measure Q1: One account's transactions for one month, newest first
        YearMonth q1Month = YearMonth.of(2026, 7);
        List<NormalizedTxn> q1Result = mongoStore.forAccountMonth("0001", q1Month);
        assertEquals(1000, q1Result.size());
        // Verify ordering: newest first
        for (int i = 1; i < q1Result.size(); i++) {
            assertTrue(!q1Result.get(i).occurredAt().isAfter(q1Result.get(i - 1).occurredAt()));
        }
        MongoDocumentStore.QueryMetrics q1Metrics = mongoStore.explainQ1("0001", q1Month);
        System.out.println("Q1 Execution Stats: totalDocsExamined=" + q1Metrics.examined() + ", nReturned=" + q1Metrics.returned());
        assertEquals(1000, q1Metrics.examined());
        assertEquals(1000, q1Metrics.returned());

        // Measure Q2: Running totals per category for an account
        Map<Category, BigDecimal> q2Result = mongoStore.categoryTotals("0001");
        assertTrue(q2Result.get(Category.SPEND).compareTo(BigDecimal.ZERO) > 0);
        MongoDocumentStore.QueryMetrics q2Metrics = mongoStore.explainQ2("0001");
        System.out.println("Q2 Execution Stats: totalDocsExamined=" + q2Metrics.examined() + ", nReturned=" + q2Metrics.returned());
        assertEquals(1, q2Metrics.examined());
        assertEquals(1, q2Metrics.returned());

        // Measure Q3: Given a message ID, which transaction did it produce
        Optional<NormalizedTxn> q3Result = mongoStore.byMessageId("mongo-msg-54321");
        assertTrue(q3Result.isPresent());
        MongoDocumentStore.QueryMetrics q3Metrics = mongoStore.explainQ3("mongo-msg-54321");
        System.out.println("Q3 Execution Stats: totalDocsExamined=" + q3Metrics.examined() + ", nReturned=" + q3Metrics.returned());
        assertEquals(1, q3Metrics.examined());
        assertEquals(1, q3Metrics.returned());
    }
}
