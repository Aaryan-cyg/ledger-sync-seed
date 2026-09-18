// MongoDB initialization script for Ledger Sync document store
db = db.getSiblingDB('ledgersync');

// 1. Transactions collection
db.createCollection('transactions');

// Index for Q1: one account's transactions for one month, newest first
// Compound index on accountLast4 ASC and occurredAt DESC allows MongoDB to satisfy
// the date range filter and sort without an in-memory sort stage (Index Scan only).
db.transactions.createIndex(
    { accountLast4: 1, occurredAt: -1 },
    { name: 'idx_account_occurred_at_desc' }
);

// Index for Q3: given a message id, which transaction did it produce
// Multikey index indexing every element in the sourceMessageIds array.
db.transactions.createIndex(
    { sourceMessageIds: 1 },
    { name: 'idx_source_message_ids_multikey' }
);

// 2. Pre-aggregated Account Category Totals collection
// Serves Q2: running totals per category for an account.
// Avoids scanning all historical transactions by maintaining a 1-document-per-account roll-up.
db.createCollection('account_category_totals');

db.account_category_totals.createIndex(
    { accountLast4: 1 },
    { name: 'idx_account_category_totals_account', unique: true }
);

print('Ledger Sync collections and indexes initialized successfully.');
