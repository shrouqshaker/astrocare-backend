# Backend verification results

Date: 2026-11-14. Linux, Node.js v22.23.4, npm 10.9.9.

Final command: npm test — exit code 0: **64 passed, 0 failed, 0 skipped**, about 6.4 seconds.

- All 34 supplied rule vectors and 5 intermediate trend vectors passed.
- Shared examples and all 126 synthetic measurements plus one symptom check-in validated.
- Zod envelope, query and configuration validation passed, including numeric types, unknown fields, UTC calendar timestamps, limits, schema versions and BP cross-field constraints.
- Real MongoDB 8.2.6 storage and exact restoration of all three types, replay, changed-content duplicates, atomic first acknowledgement, partial rejection and audit receipts passed.
- Eight concurrent identical requests produced 3 total created and 21 duplicates. Unique compound indexes and uppercase UUID replay were verified.
- History filters, true event-time sorting including fractional seconds, inclusive bounds and clean projections passed.
- Exact rule/guide bytes, ETags, authentication, request tracing and shared 400/401/404/413/503 error shapes passed.
- Explicit HTTP seeding, seed replay, synthetic replacement and manual-record preservation passed.
- The actual seed CLI inserted fixtures into a disposable database and skipped all 127 on replay.
- Trends, sample SD, evidence-backed insights, missing dates, local-date rollover and direction boundaries passed.
- Overview correctly returned insufficient_data on an empty DB and critical current status with source-linked evidence on triggering records; it did not silently persist generated alerts.
- A real spawned backend server accepted a batch and retained the saved history after restart.

npm.cmd run contracts:verify passed: original contract SHA-256 hashes, shared examples and all seed items validate. Installing dependencies reported 0 vulnerabilities.

Integration tests use disposable MongoDB databases, real indexes and HTTP requests; they never use the user's MONGODB_URI. Connections and temporary MongoDB are closed afterward. A permanent local development MongoDB is still configured separately in .env.

One earlier full run hit a subprocess timeout during seed-CLI replay. The isolated test passed; the harness timeout was increased from 20 to 60 seconds and the final full run passed. No cause beyond the observed timeout has been established.

## Honest limitations

- Trends still run in Node.js with generatedBy backend_optional, but the critical chest-pain demo now matches the frozen Python reference fixture exactly (alerts, insight UUIDv5 ids, evidence, metrics and window) in test/reference-parity.test.js. A live Python/Kotlin runtime is not invoked in production; on-device Android integration is still not tested here.
- Overview is a proposed additional endpoint because no overview route/schema exists in the frozen API. Shared contracts remain unchanged; team agreement is needed before other tracks depend on this extension.
- Android/Room/WorkManager integration, physical devices, production deployment, Atlas networking and a permanent development MongoDB service were not tested.
- Genuine mid-batch network/database fault recovery and multi-process synthetic replacement were not fault-injected. Sync and replacement are not all-or-nothing transactions. Stable IDs make normal retries safe; run replacement without concurrent writers.
- User authentication/authorization beyond the contracted demo API key is not implemented. Tests do not verify clinical suitability, flight certification, external reference claims or dataset licensing.
- No standalone settings/chest-pain/resources APIs were invented; none are defined in API.md. The supplied static fixtures remain available under contracts/data.
