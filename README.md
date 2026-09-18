# ledger-sync

Scaffolding for the Simplify Money **Software Engineering Intern (Backend, Java)** take-home.

Read this file completely before you write any code. Then read
`fixtures/corpus-a.jsonl` — not all 500 lines, but enough of them that you stop
being surprised.

> **Do not open a pull request here.** Work in your own fork and submit by email.
> PRs opened against this repository are closed automatically and are not seen
> as part of your submission.

---

## What this service is for

Simplify Money tells a user where their money went. To do that, something has to
read the bank SMS and bank emails sitting on their phone and turn them into a
ledger the user can trust.

This repository is that something, half-finished, with a live incident open
against it.

---

## What you are being asked to do, exactly

**Input:** `fixtures/corpus-a.jsonl` — one JSON object per line, each a single
SMS or email exactly as the phone uploaded it:

```json
{"message_id":"m-00004-9c11ae","channel":"sms","sender":"AD-HDFCBK-S",
 "received_at":"2026-07-04T07:19:00+05:30","device_id":"dev-3f1a90c47b21",
 "body":"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"}
```

**Output:** three JSON files, written by `report <dir>`.

### 1. `ledger.json` — one entry per real transaction

```json
{"transactions": [
  {"account_last4":"4821","occurred_at":"2026-07-04T20:24:00+05:30",
   "direction":"debit","amount":"2499.50","category":"SPEND",
   "merchant":"AMAZON PAY","source_message_ids":["m-00087-1a2b3c","m-00089-77de01"]}
]}
```

`occurred_at` is when the **bank says the transaction happened**, not when the
message arrived. `amount` always carries two decimal places and is always
positive — `direction` carries the sign. `source_message_ids` lists every
message that evidences this one transaction; there is often more than one.

### 2. `summary.json` — per-account totals

```json
{"accounts": {
  "4821": {"spend":"87068.38","income":"101340.83",
           "micro_count":52,"micro_total":"2357.51",
           "transferred_out":"25000.00","transferred_in":"6000.00"}
}}
```

### 3. `reconciliation.json` — anything your ledger cannot account for

```json
{"discrepancies": [
  {"account_last4":"4821","occurred_at":"...","amount":"...","note":"..."}
]}
```

We are not telling you how to find these, or whether there are any. Working out
what "cannot account for" means here, and what in the data lets you check it, is
part of the task.

---

## The four categories

Every transaction gets exactly one.

| Category | What it means |
|---|---|
| `SPEND` | Money left the user and is gone |
| `INCOME` | Money arrived and is theirs |
| `MICRO` | A UPI debit of **₹100 or less**. Still spending, but reported as one rolled-up line rather than listed individually |
| `TRANSFER` | One leg of the user moving their own money **between their own accounts**. Real — the money moved — but it is neither spending nor income, and counting it as either inflates both |

`micro_total` is the sum of `MICRO`. `spend` is the sum of `SPEND` and does
**not** include `MICRO` or `TRANSFER`. `income` likewise excludes `TRANSFER`.

---

## Your checkpoint

`fixtures/corpus-a-totals.json` gives you the expected transaction count, the
opening and closing balance, and the category totals for each account. No
row-level answers. Use it to check yourself.

If your numbers do not match it, **say so and say why.** A submission whose
numbers match because they were made to match is worse than one that does not
match and explains itself. We can tell the difference, and we check.

---

## Where the code is now

```
src/main/java/in/simplifymoney/ledgersync/
  model/       RawMessage, NormalizedTxn, Category, Direction
  json/        a small JSON reader/writer, so this builds with only a JDK
  parse/       one parser per message format
  ingest/      reads a corpus, saves what it finds
  store/       the SQL ledger, and the document store you are going to add
  report/      the three output documents
  App.java     migrate | ingest | report
  SelfCheck.java
```

Run it:

```bash
./verify.sh                      # compile + run the pipeline, no network needed
./gradlew test                   # the test suite (needs network once, for JUnit)
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
```

`./verify.sh` today prints 323 transactions where the totals file expects 257,
and balances that are nowhere near what the banks state. That is the starting
point, not a bug you have hit.

---

## What is missing, in the order we would do it

1. **`EmailParser` is a stub.** Every email in the corpus is currently dropped.
2. **`IciciSmsParser` reads one of the ICICI formats.** There is at least one
   more in the corpus, falling straight through.
3. **Nothing deduplicates.** `IngestService` saves one transaction per message.
   One transaction is not one message.
4. **Categories are decided from the direction alone.** No `MICRO`, no
   `TRANSFER`.
5. **`Reports.summary` adds up whatever it is given.** It does not roll micro
   spends up and does not know a transfer is not spending.
6. **`Reports.reconciliation` is not written.**
7. **`DocumentStore`, `Backfill` and `ConsistencyChecker` are interfaces with no
   implementation.** See below.
8. **`incident/INC-2026-09-11.md` is open.** Start here — it will teach you more
   about this codebase than reading it will.

---

## The document store

The ledger is moving off SQL onto a document store. **DynamoDB preferred,
MongoDB fine** — your choice, and say why. It must run from your
`docker compose up`.

`DocumentStore` declares the only three queries this service makes:

1. one account's transactions for one month, newest first
2. running totals per category for an account
3. given a message id, which transaction did it produce

Design your documents so the engine serves these directly. We are not going to
tell you what a document should look like — that decision is the exercise.

For each of the three, **report how many items the engine examined versus how
many it returned, at 100,000 transactions.** DynamoDB gives you `ScannedCount`
and `Count`; MongoDB gives you `totalDocsExamined` and `nReturned`. Put the six
numbers in your README.

Then:

- **`Backfill`** moves what is already in SQL across. Two things to know: the
  SQL store has been running without a uniqueness guarantee for a long time, and
  this will be run more than once, including after a partial failure.
- **`ConsistencyChecker`** proves the two stores agree and names precisely where
  they do not. We will run yours against a document store we have deliberately
  altered. It has to find what we changed. A checker that compares row counts
  will not.

---

## Rules

- `model/NormalizedTxn.java`, `model/Category.java` and
  `src/test/.../NormalizedTxnContractTest.java` are **frozen**. Do not edit
  them. Everything behind them is yours.
- Java. Any framework, or none — say why in your decision log.
- Real commit history. Not one squashed commit.
- If something in here is wrong or unclear, **email us**. Guessing when you
  could have asked is a worse signal than asking.

`talent.acquisition@simplifymoney.in`

---

# Implementation & Submission Report

## Quick Start & Verification

```bash
# 1. Dependency-free compilation and verification (JDK 21 alone, no network)
./verify.sh

# 2. Complete test suite (AmountsTest, NormalizedTxnContractTest, ReportsTest, DocumentStoreTest)
./gradlew test

# 3. Running the pipeline via CLI
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"

# 4. Document Store Backfill & Consistency Checker
docker compose up -d       # Starts MongoDB on port 27017
./gradlew run --args="backfill"
./gradlew run --args="check"
```

---

## 1. Incident INC-2026-09-11 Resolution

- **Reproduction Test:** `AmountsTest.reproducesIncidentInc20260911WaterCanIntegerAmount()` in [AmountsTest.java](file:///home/eyrc01aaryan/Simplify_money/ledger-sync-seed/src/test/java/in/simplifymoney/ledgersync/AmountsTest.java).
- **Root Cause File & Line:** [Amounts.java:18](file:///home/eyrc01aaryan/Simplify_money/ledger-sync-seed/src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java#L18). The regex `(?:Rs\.?|INR)\s*([0-9,]+\.[0-9]{2})` strictly mandated decimal paise (`\.[0-9]{2}`). Integer transactions (e.g. `Rs.5`) were completely skipped, causing `Amounts.first()` to latch onto the subsequent `Avl Bal: Rs.92,213.10`.
- **Blast Radius:** **44 messages** in `fixtures/corpus-a.jsonl`.
  - **Deciding Rule:** Any message where the transaction amount is an integer rupee value without decimal paise, followed by an available balance containing decimal paise.
- **The Fix:** Updated regex to `(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{2})?)` with strict 2-decimal scale padding.

### Five-Line Slack / Incident Channel Note
```
1. What broke: Regex in Amounts.java:18 mandated decimal paise, causing integer spends like Rs.5 to be skipped and the user's available balance (Rs.92,213.10) captured as the transaction amount.
2. How found: Reproduced via failing unit test reproducesIncidentInc20260911WaterCanIntegerAmount and verified against legacy SQL seed row m-legacy-0041.
3. Blast radius: 44 messages across corpus-a.jsonl where integer rupee debits were misclassified as the user's total bank balance.
4. The fix: Regex updated to (?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{2})?) to match both integer and decimal currency amounts with 2-decimal zero padding.
5. Prevention: Added regression suite in AmountsTest for integer/comma INR formats and balance-token boundary isolation.
```

---

## 2. Document Store Architecture & Benchmark Numbers

The document store is backed by a genuine MongoDB implementation in [`MongoDocumentStore.java`](file:///home/eyrc01aaryan/Simplify_money/ledger-sync-seed/src/main/java/in/simplifymoney/ledgersync/store/MongoDocumentStore.java) using the official synchronous driver (`org.mongodb:mongodb-driver-sync:5.1.4`). An in-memory store ([`DocumentLedgerStore.java`](file:///home/eyrc01aaryan/Simplify_money/ledger-sync-seed/src/main/java/in/simplifymoney/ledgersync/store/DocumentLedgerStore.java)) is also retained for fast, dependency-free unit testing.

### How to Start MongoDB
- **With Docker:**
  ```bash
  docker compose up -d
  ```
  Starts MongoDB 7.0 on port `27017` with collection initialization and indexes provisioned via [`docker/mongo-init.js`](file:///home/eyrc01aaryan/Simplify_money/ledger-sync-seed/docker/mongo-init.js).
- **Connection resolution:** Connects to `mongodb://localhost:27017` by default, or uses the `MONGODB_URI` environment variable if specified. Database name is `ledgersync`.

### Document Schemas & Indexes

1. **`transactions` Collection:**
   ```json
   {
     "_id": "4821_2026-07-04T20:24:00+05:30_DEBIT_2499.50",
     "accountLast4": "4821",
     "occurredAt": "2026-07-04T20:24:00+05:30",
     "direction": "DEBIT",
     "amount": Decimal128("2499.50"),
     "category": "SPEND",
     "merchant": "AMAZON PAY",
     "sourceMessageIds": ["m-00087-1a2b3c", "m-00089-77de01"]
   }
   ```
   - **Primary Key (`_id`):** Natural key `accountLast4_occurredAt_direction_amount` ensures absolute uniqueness at the database engine level.
   - **Monetary Precision:** Stored as BSON `Decimal128` to maintain positive two-decimal exact arithmetic without IEEE-754 floating-point inaccuracies.
   - **Compound Index:** `{ accountLast4: 1, occurredAt: -1 }` directly serves Query 1.
   - **Multikey Index:** `{ sourceMessageIds: 1 }` directly serves Query 3.

2. **`account_category_totals` Collection:**
   ```json
   {
     "_id": "4821",
     "accountLast4": "4821",
     "totals": {
       "SPEND": Decimal128("174988.46"),
       "INCOME": Decimal128("151340.83"),
       "MICRO": Decimal128("2357.51"),
       "TRANSFER": Decimal128("31000.00")
     }
   }
   ```
   - Maintained atomically upon transaction write (`$inc`). Direct point lookup serves Query 2.

### Benchmark Numbers at 100,000 Transactions in MongoDB

Measured directly against live MongoDB using `explain(ExplainVerbosity.EXECUTION_STATS)` in [`MongoDocumentStoreTest.test100kBenchmarkAgainstMongo()`](file:///home/eyrc01aaryan/Simplify_money/ledger-sync-seed/src/test/java/in/simplifymoney/ledgersync/MongoDocumentStoreTest.java) with 100,000 documents distributed across 10 accounts and 10 months (1,000 transactions for the queried account in the target month):

| Query Access Pattern | Engine Metric (`totalDocsExamined`) | Engine Metric (`nReturned`) | Ratio | Performance Mechanism in MongoDB |
|---|:---:|:---:|:---:|---|
| **Q1: One account's transactions for one month, newest first** | **1,000** | **1,000** | **1:1** | B-Tree index range scan on compound index `{ accountLast4: 1, occurredAt: -1 }`. MongoDB traverses matching keys backwards, satisfying both the month date filter and the descending order without an in-memory `SORT` stage. |
| **Q2: Running totals per category for an account** | **1** | **1** | **1:1** | Point lookup on `account_category_totals` by `accountLast4` unique primary key. Pre-aggregated rollup document completely eliminates scanning 10,000 historical transactions. |
| **Q3: Given a message ID, which transaction did it produce** | **1** | **1** | **1:1** | Multikey index point lookup on `sourceMessageIds`. Direct B-Tree hop directly to the matching transaction document. |

### Backfill & Consistency Checker Against MongoDB
- **Backfill Idempotency:**
  - First run: `read=271, written=266, skipped=0` (groups legacy rows from `V2__seed.sql` to eliminate duplicates).
  - Second run: `read=271, written=0, skipped=266` (100% idempotent; writes zero duplicates).
- **Consistency Checker:**
  - `stores agree completely (0 divergences)`.
  - Field-level verification catches any altered `amount`, `category`, `occurredAt`, `direction`, or `sourceMessageIds` in MongoDB.

---

## 3. Decision Log

1. **Plain Java 21 over Spring Boot / Frameworks:**
   - *Rationale:* Financial batch and message ingestion engines need near-zero cold start overhead and predictable memory. Avoiding Spring/Jackson ensures `./verify.sh` compiles and executes completely offline with `javac` and standard JDK 21.
2. **Exact Financial Arithmetic (`BigDecimal` with Scale 2):**
   - *Rationale:* IEEE-754 floating point arithmetic (`float`/`double`) introduces decimal rounding errors fatal to banking ledgers. All money calculations use `BigDecimal` with scale 2 and `RoundingMode.UNNECESSARY`.
3. **Multi-Channel Deduplication Natural Key:**
   - *Rationale:* In Indian banking, a single real-world spend produces both an SMS and an email. Transactions are grouped by natural key `(accountLast4, occurredAt, direction, amount)` with `sourceMessageIds` combined into a sorted, distinct list.
4. **UTC Email Date Normalization to IST (+05:30):**
   - *Rationale:* Inspection revealed that email `m-00131-cd229b` had an RFC 2822 date in UTC (`+0000`), while its SMS counterpart `m-00130-a9be28` had an IST timestamp (`+05:30`). Normalizing both to IST truncated to the minute enabled exact cross-channel deduplication.
5. **Credit Card (`CARD`) Limit vs Bank Balance:**
   - *Rationale:* Account 3310 messages quote `Avl Limit`, not an available bank balance. Setting `statedBalance = null` on credit card notifications prevents false balance divergence alerts in reconciliation reports.
6. **Symmetric Transfer Detection Window:**
   - *Rationale:* Self-transfers between user accounts (e.g. 4821 and 9075) appear as a debit on one account and a credit on another within a short duration. We pair debit/credit legs of equal amounts across different accounts occurring within 15 minutes, categorizing them as `TRANSFER` and excluding them from spend and income.
7. **Honest Reconciliation & The ₹7,500.00 Discrepancy on Account 4821:**
   - *Rationale:* Between 2026-07-29 11:53 (balance 36,054.05) and 17:06 (balance 28,479.05), the bank balance dropped by 7,575.00, but the corpus contains only a single 75.00 debit message (`m-00204-4741e7`). Synthesizing a fake message or ghost transaction would violate the `NormalizedTxn` contract ("must cite at least one message"). We surface this honest discrepancy in `reconciliation.json`.
8. **Document Store Choice (MongoDB over DynamoDB):**
   - *Rationale:* MongoDB was selected for local developer reproducibility via Docker (`mongo:7.0`), rich expressive BSON queries, compound index support with sorting (`occurredAt: -1`), and native multikey indexing on array fields (`sourceMessageIds`).
9. **Pre-aggregated Category Totals Document:**
   - *Rationale:* Query 2 requires running totals per category for an account. An aggregation pipeline across 100k transactions would have an O(N) scan cost. Maintaining an `account_category_totals` rollup document turns Q2 into an O(1) point lookup (`examined: 1, returned: 1`).
10. **Idempotent Backfill & Deep Field Consistency Checker:**
    - *Rationale:* Legacy SQL rows in `V2__seed.sql` contained duplicates with missing uniqueness guarantees. `Backfill` groups legacy rows by natural key, merges `sourceMessageIds`, and checks existing documents before write, making it partial-failure safe. `ConsistencyChecker` verifies stores at the field level (amount, direction, category, timestamp, account, sourceMessageIds, and categoryTotals), catching deliberate alterations.

---

## 4. What the Data Made Us Decide

1. **Email Date Parsing & Timezone Offsets:**
   - `fixtures/corpus-a.jsonl` contains emails formatted according to RFC 1123/2822. Specifically, `m-00131-cd229b` arrived with timestamp `Sat, 18 Jul 2026 18:50:00 +0000` (UTC). Converted to IST, it is `2026-07-19T00:20:00+05:30`, which exactly matches SMS `m-00130-a9be28` (`Rs 412.67 debited on 19-07-26 at 00:20`). Normalizing email dates to IST was essential to prevent duplicate ledger entries.
2. **Credit Limits Are Not Bank Balances:**
   - Account 3310 is an HDFC Credit Card. Messages for 3310 quote `Avl Limit: Rs.2,76,058.85`. Treating this as an account balance caused massive false divergence alerts. Stated balances are ignored for credit cards.
3. **The Missing ₹7,500.00 Transaction:**
   - Ground truth in `fixtures/corpus-a-totals.json` expects 146 transactions for account 4821. However, `corpus-a.jsonl` physically contains only 145 messages for 4821. An unannounced ₹7,500.00 drop occurred between 11:53 and 17:06 on July 29, 2026. Because `NormalizedTxn` mandates real evidence in `sourceMessageIds`, the ledger honestly records 145 transactions and flags the ₹7,500.00 discrepancy in `reconciliation.json`.
4. **Duplicate Seed Rows in `V2__seed.sql`:**
   - `V2__seed.sql` contains duplicate rows for `m-legacy-0001` (Swiggy ₹449.00) and `m-legacy-0007` (Myntra ₹1299.50). This forced us to make `Backfill` group and merge legacy rows by natural transaction identity.

---

## 5. AI Disclosure

- **Tools Used:** Claude / Antigravity CLI for pair-programming and code generation.
- **Where AI Succeeded:**
  - Rapid scaffolding of regex patterns for HDFC and ICICI SMS templates.
  - Generating RFC 1123 date parsing logic.
  - Structuring test harnesses and generating 100,000 synthetic transaction records for benchmark testing.
- **Where AI Failed & Required Human Intervention:**
  - *Naive Timestamps:* Initial AI suggestions parsed email dates in local system timezone rather than standardizing to IST (+05:30), breaking cross-channel deduplication.
  - *Fudging Totals:* An initial AI prompt suggested synthesizing a phantom transaction to force 4821 to match 146 transactions. This was rejected because it violates the `NormalizedTxn` contract and the assignment requirement for honest reconciliation.
  - *H2 2.2 Compatibility:* H2 2.2 in `MODE=PostgreSQL` rejected `IDENTITY PRIMARY KEY` syntax; this was diagnosed and resolved by removing the strict PostgreSQL mode flag in `SqlLedgerStore`.

---

## 6. What's Unfinished / Next Production Hardening

1. **Distributed Stream Processing:** Replace file-based batch ingest with an Apache Kafka or AWS Kinesis pipeline partitioned by `accountLast4`.
2. **Transactional Outbox & Change Data Capture (CDC):** Use Debezium on MongoDB replica set change streams to update Elasticsearch / read replicas asynchronously.
3. **Distributed Lock for Transfer Pairing:** For high-throughput concurrent streams, use Redis/Redlock to lock account pairs when reconciling cross-account transfer legs.
4. **LLM/NLP Fallback Parser:** Deploy a lightweight local LLM (e.g. Gemma-2B) fallback parser for unrecognized bank SMS templates that fail regex extraction.
