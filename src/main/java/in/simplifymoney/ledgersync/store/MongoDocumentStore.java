package in.simplifymoney.ledgersync.store;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

/**
 * Production MongoDB implementation of DocumentStore.
 *
 * Connects to MongoDB (default mongodb://localhost:27017 or MONGODB_URI).
 * Models documents directly to serve the three required access patterns:
 *  1. Monthly transactions: compound index (accountLast4 ASC, occurredAt DESC)
 *  2. Running category totals: pre-aggregated account document in account_category_totals
 *  3. Message-to-transaction lookup: multikey index on sourceMessageIds
 */
public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    public static final String DEFAULT_URI = "mongodb://localhost:27017";
    public static final String DEFAULT_DATABASE = "ledgersync";

    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<Document> txns;
    private final MongoCollection<Document> totals;

    public MongoDocumentStore() {
        this(resolveUri(), DEFAULT_DATABASE);
    }

    public MongoDocumentStore(String uri) {
        this(uri, DEFAULT_DATABASE);
    }

    public MongoDocumentStore(String uri, String dbName) {
        this(MongoClients.create(uri), dbName);
    }

    public MongoDocumentStore(MongoClient client, String dbName) {
        this.client = client;
        this.database = client.getDatabase(dbName);
        this.txns = database.getCollection("transactions");
        this.totals = database.getCollection("account_category_totals");
        initIndexes();
    }

    public static String resolveUri() {
        String env = System.getenv("MONGODB_URI");
        return (env != null && !env.isBlank()) ? env : DEFAULT_URI;
    }

    public void initIndexes() {
        // Compound index for Q1: { accountLast4: 1, occurredAt: -1 }
        txns.createIndex(
                Indexes.compoundIndex(Indexes.ascending("accountLast4"), Indexes.descending("occurredAt")),
                new IndexOptions().name("idx_account_occurred_at_desc")
        );

        // Multikey index for Q3: { sourceMessageIds: 1 }
        txns.createIndex(
                Indexes.ascending("sourceMessageIds"),
                new IndexOptions().name("idx_source_message_ids_multikey")
        );

        // Unique index for Q2: { accountLast4: 1 }
        totals.createIndex(
                Indexes.ascending("accountLast4"),
                new IndexOptions().name("idx_account_category_totals_account").unique(true)
        );
    }

    @Override
    public void save(NormalizedTxn txn) {
        String docId = docIdOf(txn);
        Document existing = txns.find(Filters.eq("_id", docId)).first();

        if (existing == null) {
            Document doc = new Document("_id", docId)
                    .append("accountLast4", txn.accountLast4())
                    .append("occurredAt", txn.occurredAt().toString())
                    .append("direction", txn.direction().name())
                    .append("amount", new Decimal128(txn.amount().setScale(2)))
                    .append("category", txn.category().name())
                    .append("merchant", txn.merchant())
                    .append("sourceMessageIds", txn.sourceMessageIds());
            txns.insertOne(doc);

            // Increment category running totals for the account
            totals.updateOne(
                    Filters.eq("accountLast4", txn.accountLast4()),
                    Updates.inc("totals." + txn.category().name(), new Decimal128(txn.amount().setScale(2))),
                    new UpdateOptions().upsert(true)
            );
        } else {
            // Merge sourceMessageIds and update category / amount if changed
            List<String> existingIds = existing.getList("sourceMessageIds", String.class);
            List<String> mergedIds = new ArrayList<>();
            if (existingIds != null) mergedIds.addAll(existingIds);
            for (String id : txn.sourceMessageIds()) {
                if (!mergedIds.contains(id)) mergedIds.add(id);
            }
            Collections.sort(mergedIds);

            Category oldCat = Category.valueOf(existing.getString("category"));
            Decimal128 oldAmt = existing.get("amount", Decimal128.class);
            Decimal128 newAmt = new Decimal128(txn.amount().setScale(2));

            txns.updateOne(
                    Filters.eq("_id", docId),
                    Updates.combine(
                            Updates.set("sourceMessageIds", mergedIds),
                            Updates.set("category", txn.category().name()),
                            Updates.set("amount", newAmt),
                            Updates.set("merchant", txn.merchant())
                    )
            );

            if (oldCat != txn.category() || !oldAmt.equals(newAmt)) {
                totals.updateOne(
                        Filters.eq("accountLast4", txn.accountLast4()),
                        Updates.combine(
                                Updates.inc("totals." + oldCat.name(), new Decimal128(oldAmt.bigDecimalValue().negate())),
                                Updates.inc("totals." + txn.category().name(), newAmt)
                        ),
                        new UpdateOptions().upsert(true)
                );
            }
        }
    }

    /**
     * Batch insert utility for high-scale benchmarks and fast setup.
     */
    public void saveBatch(List<NormalizedTxn> list) {
        if (list == null || list.isEmpty()) return;
        List<Document> docs = new ArrayList<>(list.size());
        Map<String, Map<Category, BigDecimal>> acctTotalsDelta = new LinkedHashMap<>();

        for (NormalizedTxn t : list) {
            String docId = docIdOf(t);
            Document doc = new Document("_id", docId)
                    .append("accountLast4", t.accountLast4())
                    .append("occurredAt", t.occurredAt().toString())
                    .append("direction", t.direction().name())
                    .append("amount", new Decimal128(t.amount().setScale(2)))
                    .append("category", t.category().name())
                    .append("merchant", t.merchant())
                    .append("sourceMessageIds", t.sourceMessageIds());
            docs.add(doc);

            acctTotalsDelta.computeIfAbsent(t.accountLast4(), k -> new LinkedHashMap<>())
                    .merge(t.category(), t.amount(), BigDecimal::add);
        }

        txns.insertMany(docs, new InsertManyOptions().ordered(false));

        for (Map.Entry<String, Map<Category, BigDecimal>> e : acctTotalsDelta.entrySet()) {
            List<Bson> incs = new ArrayList<>();
            for (Map.Entry<Category, BigDecimal> ce : e.getValue().entrySet()) {
                incs.add(Updates.inc("totals." + ce.getKey().name(), new Decimal128(ce.getValue().setScale(2))));
            }
            totals.updateOne(
                    Filters.eq("accountLast4", e.getKey()),
                    Updates.combine(incs),
                    new UpdateOptions().upsert(true)
            );
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        String startStr = month.toString() + "-01";
        String endStr = month.plusMonths(1).toString() + "-01";

        Bson filter = Filters.and(
                Filters.eq("accountLast4", accountLast4),
                Filters.gte("occurredAt", startStr),
                Filters.lt("occurredAt", endStr)
        );
        Bson sort = Sorts.descending("occurredAt");

        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find(filter).sort(sort)) {
            out.add(toNormalizedTxn(d));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Document doc = totals.find(Filters.eq("accountLast4", accountLast4)).first();
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            out.put(c, BigDecimal.ZERO.setScale(2));
        }
        if (doc != null && doc.get("totals") instanceof Document sub) {
            for (Category c : Category.values()) {
                Object val = sub.get(c.name());
                if (val instanceof Decimal128 d) {
                    out.put(c, d.bigDecimalValue().setScale(2));
                } else if (val instanceof Number n) {
                    out.put(c, new BigDecimal(n.toString()).setScale(2));
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document doc = txns.find(Filters.eq("sourceMessageIds", messageId)).first();
        if (doc == null) return Optional.empty();
        return Optional.of(toNormalizedTxn(doc));
    }

    public record QueryMetrics(long examined, long returned) {}

    public QueryMetrics explainQ1(String accountLast4, YearMonth month) {
        String startStr = month.toString() + "-01";
        String endStr = month.plusMonths(1).toString() + "-01";
        Bson filter = Filters.and(
                Filters.eq("accountLast4", accountLast4),
                Filters.gte("occurredAt", startStr),
                Filters.lt("occurredAt", endStr)
        );
        Bson sort = Sorts.descending("occurredAt");
        Document explain = txns.find(filter).sort(sort).explain(ExplainVerbosity.EXECUTION_STATS);
        Document execStats = explain.get("executionStats", Document.class);
        long examined = execStats != null ? execStats.getInteger("totalDocsExamined", 0) : 0L;
        long returned = execStats != null ? execStats.getInteger("nReturned", 0) : 0L;
        return new QueryMetrics(examined, returned);
    }

    public QueryMetrics explainQ2(String accountLast4) {
        Bson filter = Filters.eq("accountLast4", accountLast4);
        Document explain = totals.find(filter).explain(ExplainVerbosity.EXECUTION_STATS);
        Document execStats = explain.get("executionStats", Document.class);
        long examined = execStats != null ? execStats.getInteger("totalDocsExamined", 0) : 0L;
        long returned = execStats != null ? execStats.getInteger("nReturned", 0) : 0L;
        return new QueryMetrics(examined, returned);
    }

    public QueryMetrics explainQ3(String messageId) {
        Bson filter = Filters.eq("sourceMessageIds", messageId);
        Document explain = txns.find(filter).explain(ExplainVerbosity.EXECUTION_STATS);
        Document execStats = explain.get("executionStats", Document.class);
        long examined = execStats != null ? execStats.getInteger("totalDocsExamined", 0) : 0L;
        long returned = execStats != null ? execStats.getInteger("nReturned", 0) : 0L;
        return new QueryMetrics(examined, returned);
    }

    public int totalDocuments() {
        return (int) txns.countDocuments();
    }

    public void clear() {
        txns.deleteMany(new Document());
        totals.deleteMany(new Document());
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {}
    }

    private static String docIdOf(NormalizedTxn t) {
        return t.accountLast4() + "_" + t.occurredAt() + "_" + t.direction() + "_" + t.amount().toPlainString();
    }

    private static NormalizedTxn toNormalizedTxn(Document doc) {
        String acct = doc.getString("accountLast4");
        OffsetDateTime when = OffsetDateTime.parse(doc.getString("occurredAt"));
        Direction dir = Direction.valueOf(doc.getString("direction"));
        BigDecimal amt = doc.get("amount", Decimal128.class).bigDecimalValue().setScale(2);
        Category cat = Category.valueOf(doc.getString("category"));
        String merchant = doc.getString("merchant");
        List<String> srcIds = doc.getList("sourceMessageIds", String.class);
        return new NormalizedTxn(acct, when, dir, amt, cat, merchant, srcIds);
    }
}
