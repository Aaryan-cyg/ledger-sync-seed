# What's Unfinished

The core requirements of the assignment are implemented and verified. The following items remain outside the completed scope or would be areas for further work.

## 1. Unexplained ₹7,500 reconciliation difference

The supplied corpus contains an unexplained ₹7,500 balance difference for account `4821`. I investigated the available messages and could not find source evidence for the missing transaction.

I intentionally did not create an unsupported transaction just to make the balance reconcile. The difference is recorded in `reconciliation.json`.

With access to the original bank statement or the missing notification, I would investigate this further and identify the exact transaction.

## 2. Additional corpus coverage

The implementation has been tested against the supplied corpus and the existing test cases. The assignment will run the service against a different corpus after submission.

With more time, I would add more synthetic cases for message variations, duplicate evidence, delayed messages, out-of-order messages, transfers, and ambiguous transaction descriptions.

## 3. Larger-scale performance testing

The required 100,000-transaction document-store benchmark was completed for the three specified query patterns.

With more time, I would run longer load tests with more realistic transaction distributions and concurrent requests to understand performance under sustained production-like workloads.

## 4. Production deployment

I did not deploy the service to a public URL because deployment was optional for this assignment.

The application and MongoDB document store can be started locally using the documented Docker/application commands.

## 5. Additional production hardening

With more time, I would add further production-oriented monitoring, failure recovery scenarios, and operational metrics around ingestion, database errors, and reconciliation failures.