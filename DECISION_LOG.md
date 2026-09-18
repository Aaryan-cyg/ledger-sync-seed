# Decision Log

This document records the main engineering decisions made while completing the Simplify Money Ledger Sync assignment. The focus is on what I considered, what I chose, why I chose it, and what I learned from the data and testing.

## 1. Choosing MongoDB over DynamoDB

The assignment preferred DynamoDB but also allowed MongoDB. I considered both options and selected MongoDB because it was straightforward to run locally through Docker and integrate with the Java application. The three required query patterns also map naturally to MongoDB queries and indexes. Using the official MongoDB Java driver kept the implementation close to the database without adding unnecessary abstraction.

**What I rejected:** DynamoDB.

**Why:** For this assignment, local reproducibility, simple Docker-based setup, and direct query support were more useful to me than choosing the preferred option purely because it was preferred.

---

## 2. Treating `message_id` as evidence rather than transaction identity

The corpus specifies that `message_id` identifies an uploaded message, not the underlying financial transaction. I therefore did not use `message_id` as the unique identity of a transaction. Multiple messages can provide evidence for the same transaction, so the normalized transaction keeps all relevant `source_message_ids`.

This also makes the ledger traceable: starting from a ledger transaction, the system can identify the messages that produced it.

**What I rejected:** Creating exactly one ledger transaction for every input message.

**Why:** That would create duplicates when several messages describe the same financial event.

---

## 3. Deduplicating transactions instead of messages

The corpus contains duplicate or overlapping evidence, so deduplication had to happen at the transaction level. I normalized messages first and then used the transaction's identifying fields to prevent the same financial event from appearing more than once.

I also made the persistence operation idempotent so that processing the same or overlapping corpus again does not create additional transactions.

**What I rejected:** Treating every successfully parsed message as a new transaction.

**Why:** The assignment requires every real transaction to appear exactly once, even when multiple messages evidence it.

---

## 4. Using transaction semantics instead of direction alone for categories

I did not classify every debit as `SPEND` and every credit as `INCOME`. The assignment defines four categories: `SPEND`, `INCOME`, `MICRO`, and `TRANSFER`. A UPI debit of ₹100 or less is `MICRO`, while movement between the user's own accounts is `TRANSFER`.

Therefore, direction is only one part of categorization. The message content, amount, account information, and transaction context are also important.

**What I rejected:** `debit -> SPEND` and `credit -> INCOME` as the complete categorization rule.

**Why:** That would incorrectly count transfers as spending/income and would miss the special treatment of micro transactions.

---

## 5. Using the bank transaction time for `occurred_at`

The corpus contains a message arrival time, but the ledger needs the time at which the bank says the transaction happened. I therefore used the transaction timestamp from the bank message for `occurred_at`, rather than assuming that `received_at` represented the transaction time.

This matters because a message can arrive after the actual transaction.

**What I rejected:** Using `received_at` as the transaction timestamp.

**Why:** It represents message delivery/upload timing, not necessarily when the financial event occurred.

---

## 6. Keeping MICRO transactions in the ledger but aggregating them in the summary

I kept every `MICRO` transaction as an individual ledger entry while aggregating them into `micro_count` and `micro_total` in `summary.json`.

This keeps the ledger traceable while still following the reporting requirement that micro spending is rolled up in the account summary.

**What I rejected:** Removing individual micro transactions from the ledger completely.

**Why:** A micro transaction is still a real transaction and should remain traceable in `ledger.json`.

---

## 7. Reporting the unexplained ₹7,500 reconciliation discrepancy honestly

During reconciliation, account `4821` showed a ₹7,500 difference between the ledger-derived balance and the bank checkpoint. I investigated the surrounding messages, searched the corpus for evidence of a ₹7,500 transaction, and checked whether an existing message could account for the difference.

I could not find a source notification supporting such a transaction. I therefore did not create a transaction just to make the totals match. Instead, I recorded the discrepancy in `reconciliation.json` and documented the investigation.

**What I rejected:** Adding an unsupported ₹7,500 transaction or modifying the expected totals.

**Why:** Every ledger transaction needs source evidence, and the assignment explicitly values honest reconciliation over artificially matching numbers.

---

## 8. Designing MongoDB around the three required query patterns

The document model was designed around the actual access patterns specified by the assignment rather than around a generic relational-to-document conversion.

The main transaction access pattern uses the account and transaction time, so I added an index supporting account/month retrieval in newest-first order. I also indexed source message IDs so that a message can be traced back to the transaction it produced. Category totals are maintained separately so the account-level category query can be served directly.

**What I rejected:** Keeping a completely unindexed transaction collection and scanning it for every query.

**Why:** The assignment explicitly asks for examined-versus-returned measurements at 100,000 transactions, so the storage design should support the required queries efficiently.

---

## 9. Making the document-store backfill idempotent

The assignment warns that the SQL store has operated without a uniqueness guarantee and that backfill may be run multiple times, including after a partial failure. I therefore designed the document-store save operation around a deterministic transaction identity and made repeated backfill runs safe.

I tested the behavior by running the backfill more than once. The first run inserted the missing documents, while the second run did not create duplicates.

**What I rejected:** Blindly inserting every SQL row every time backfill runs.

**Why:** That would make repeated execution create duplicate documents and would not be safe after partial failure.

---

## 10. Comparing transaction content in the ConsistencyChecker

For consistency checking, I did not rely only on the number of rows/documents in each store. Two stores can have the same number of transactions while one transaction has been changed.

The checker therefore compares transaction content and reports the specific divergence rather than only reporting a count mismatch. This makes it capable of detecting a deliberately altered document while still having the same number of records.

**What I rejected:** A row-count-only consistency check.

**Why:** Matching counts do not prove that the actual transaction data agrees between SQL and the document store.

---

## Notes for review

Before submitting, I will review each entry and replace generic wording with the exact details of what I personally observed, tested, rejected, and learned during the implementation. In particular, the reconciliation decision and the backfill/consistency results should reflect the actual commands and outputs from my run.
