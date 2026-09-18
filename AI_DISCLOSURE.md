# AI Disclosure

## How I Used AI

I used AI tools during this assignment as an engineering assistant, not as a replacement for implementation or verification.

I used ChatGPT for:
- understanding and breaking down the assignment requirements
- discussing possible implementation approaches
- debugging Java and Gradle issues
- discussing parser, deduplication, categorization, and reconciliation logic
- reviewing the MongoDB document-store design
- helping structure tests and documentation
- reviewing the final implementation and identifying areas that needed verification

I still ran the code, tests, verification script, database checks, backfill, and consistency checks myself. I treated AI suggestions as suggestions and verified them against the repository, corpus, and actual command output.

## Concrete Example Where AI Output Was Less Reliable Than My Final Work

### Situation

While working on the corpus reconciliation, the generated ledger did not fully match the balance checkpoint for account `4821`. There was an unexplained difference of ₹7,500.

### Initial AI Direction / Risk

The initial analysis around the mismatch focused on finding a transaction that could explain the expected balance difference and on checking whether the ledger could be adjusted to reconcile with the supplied totals.

The important problem with that direction was that a matching number is not enough. A transaction should not be added to the ledger unless the input data provides evidence for it.

### What I Did

I investigated the raw corpus directly. I checked the messages around the two balance checkpoints, searched the corpus for a ₹7,500 transaction, checked the sequence of message IDs, and reviewed the parsed transactions and skipped messages.

The investigation did not find a source notification that supported a ₹7,500 transaction.

### Final Decision

I did not create a transaction without evidence just to make the balance match. I kept the ledger evidence-based and recorded the ₹7,500 difference in `reconciliation.json` as an unexplained discrepancy.

### Difference

The final implementation prioritizes traceability and evidence over forcing the output to match an expected number. This was an example where independent investigation of the actual data was more important than accepting an AI-generated direction without verification.

## What I Learned From Using AI

AI was useful for quickly exploring implementation options and debugging, but it could not replace checking the actual corpus and running the system. For this assignment, the most important verification came from the repository, raw input messages, test results, database behavior, and reconciliation output.

I therefore used AI mainly to accelerate development and reasoning, while keeping the final technical decisions based on evidence from the code and data.
