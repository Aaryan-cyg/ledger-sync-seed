package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsTest {

    @Test
    void ingestsAndReconcilesCorpusA() throws IOException {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        assertEquals(522, stats.messagesRead());
        assertEquals(256, stats.transactionsWritten());
        assertEquals(41, stats.messagesSkipped());
        assertEquals(256, store.count());

        // Test idempotency: re-running ingests nothing new
        IngestService.Stats stats2 = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));
        assertEquals(522, stats2.messagesRead());
        assertEquals(0, stats2.transactionsWritten());
        assertEquals(256, store.count());

        // Test summary
        List<NormalizedTxn> ledger = store.all();
        Map<String, Object> summary = Reports.summary(ledger);
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) summary.get("accounts");

        @SuppressWarnings("unchecked")
        Map<String, Object> acct9075 = (Map<String, Object>) accounts.get("9075");
        assertEquals("39058.11", acct9075.get("spend"));
        assertEquals("41450.33", acct9075.get("income"));
        assertEquals(45, acct9075.get("micro_count"));
        assertEquals("2086.34", acct9075.get("micro_total"));
        assertEquals("6000.00", acct9075.get("transferred_out"));
        assertEquals("25000.00", acct9075.get("transferred_in"));

        @SuppressWarnings("unchecked")
        Map<String, Object> acct4821 = (Map<String, Object>) accounts.get("4821");
        assertEquals("101340.83", acct4821.get("income"));
        assertEquals(52, acct4821.get("micro_count"));
        assertEquals("2357.51", acct4821.get("micro_total"));
        assertEquals("25000.00", acct4821.get("transferred_out"));
        assertEquals("6000.00", acct4821.get("transferred_in"));

        // Test reconciliation: finds the exact 7500.00 unaccounted discrepancy on 4821
        Map<String, Object> rec = Reports.reconciliation(ledger);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) rec.get("discrepancies");
        assertEquals(1, discrepancies.size());
        assertEquals("4821", discrepancies.get(0).get("account_last4"));
        assertEquals("7500.00", discrepancies.get(0).get("amount"));
        assertTrue(((String) discrepancies.get(0).get("note")).contains("Unaccounted balance divergence"));
    }
}
