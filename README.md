# AstroCare backend — shared contracts v1.0.0

Runnable Node.js 22+ / Express / MongoDB / Mongoose / Zod backend. The supplied shared schemas, examples, rules, guides and synthetic seed are unchanged under `contracts/`. AI/Data integration and the UUIDv5 parity fixes are documented in [docs/AI-DATA-INTEGRATION.md](docs/AI-DATA-INTEGRATION.md).

The earlier prototype is archived in `archive/pre-contract-v1/` in the workspace. The active API uses **`id`, not `clientId`**, and **`/api/v1/sync/batch`**, not `/sync`. See [compatibility decisions](docs/BACKEND-DECISIONS.md) for the overview extension, Node trends substitution and explicitly documented interpretations. No automatic data migration is performed.

## Run locally

Install Node.js 22+ and MongoDB Community Server, or use a development MongoDB connection URI. Start MongoDB before the backend. The test-only temporary MongoDB does not serve as your permanent development database.

PowerShell, from the project root:

```powershell
npm.cmd ci
Copy-Item .env.example .env
# Edit .env: MONGODB_URI and API_KEY
npm.cmd start
```

On macOS/Linux use `npm` and `cp .env.example .env`. `npm run dev` starts Node's watch mode. A local MongoDB URI is prefilled; the backend does not install or start a permanent MongoDB service. For a manual local MongoDB process, after installing `mongod`:

```powershell
New-Item -ItemType Directory -Path .cache/mongo-dev -Force
mongod --dbpath .cache/mongo-dev --bind_ip 127.0.0.1 --port 27017
```

Leave that terminal running and start the backend in another. MongoDB data persists under `.cache/mongo-dev`; stopping the process does not delete it. Never point tests at your development database.

Configuration:

| Variable | Meaning |
| --- | --- |
| `PORT` | Default 3000 |
| `MONGODB_URI` | Required MongoDB connection URI |
| `API_KEY` | Required; example `demo-key` is for local demo only |
| `CONTRACTS_DIR` | Defaults to bundled `contracts/`; relative example is resolved from working directory |
| `LOG_LEVEL` | `info`, `error`, or `silent` |
| `PYTHON_BIN` | Reserved; included Node trend implementation does not use Python |

Startup waits for MongoDB and all indexes, including unique `(astronautId,id)` per data collection. A startup failure exits with code 1. Nothing is seeded on startup, sync, history or analytics reads.

## Explicit demo seed

```powershell
npm.cmd run seed
# Optional reset of synthetic records only; preserve manual records:
npm.cmd run seed -- --replace
```

First seed on a clean database returns:

```json
{"inserted":{"measurements":126,"symptomCheckins":1},"skipped":0}
```

Re-running without replacement skips all 127 fixture records. The equivalent authenticated HTTP command is `POST /api/v1/demo/seed` with `{"astronautId":"demo-astronaut-01","replace":false}`. Replacement is a demo maintenance operation; don't run it concurrently with sync writers.

The seed is synthetic and covers **2026-10-31 through 2026-11-13**. Values are not NASA astronaut data. Trend windows follow the current server clock, so historic fixtures eventually fall outside the current seven-day window; timestamps are never silently shifted. Clinical/reference flags are preserved. See `docs/upstream/DATASETS.md` and `contracts/data/rules.v1.json`.

## Requests for Android / Postman

Use `Content-Type: application/json` and `X-API-Key` on all API calls except health. Every response includes `X-Request-Id`. Complete sample batch containing measurement, symptom check-in and alert evidence: [example-sync.json](example-sync.json), copied from the shared contract example.

```powershell
$headers = @{ 'X-API-Key' = 'demo-key' } # use your configured value
Invoke-RestMethod 'http://localhost:3000/api/v1/health'
Invoke-RestMethod -Method Post -Uri 'http://localhost:3000/api/v1/sync/batch' -Headers $headers -ContentType 'application/json' -Body (Get-Content -Raw example-sync.json)
Invoke-RestMethod 'http://localhost:3000/api/v1/astronauts/demo-astronaut-01/measurements?metric=heart_rate&limit=200' -Headers $headers
```

The sample on a clean database returns the shared `SyncBatchResponse`: `summary: {created:3, duplicates:0, rejected:0}` and per-group results with `id` and `status:"created"`. Replaying returns `summary: {created:0, duplicates:3, rejected:0}` with statuses `duplicate`. If the seed was already loaded, the measurement/check-in in the example may already be duplicates. Full response shape: `contracts/examples/sync-response.json`.

The envelope requires `schemaVersion`, `batchId`, `astronautId`, `deviceId`, `sentAt`, `clientRuleSetVersion`, and `items` containing all three arrays (`measurements`, `symptomCheckins`, `alerts`, at most 100 items each). Keep the same record UUID `id` across WorkManager retries; it plays the idempotency role previously called `clientId`. Only `demo-astronaut-01` is available.

Bad envelopes return 400. Valid envelopes return 200 with per-item `created`, `duplicate` or `rejected`; bad items do not prevent good items being saved. Validation errors include their code/message and details where applicable. Blood pressure requires systolic > diastolic. Existing content wins for reused IDs; only an alert's first acknowledgement may be added. A network/storage failure may follow partial writes: retry with the same IDs. No 207 or invented `conflict` status is returned.

## Implemented endpoints

All paths below are relative to `/api/v1`.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/health` | No auth; readiness, contract version, UTC time; 503 when DB is unavailable |
| POST | `/sync/batch` | Idempotent per-item sync, alert acknowledgement and audit |
| GET | `/astronauts/:astronautId/measurements` | `metric`, `from`, `to`, `limit` |
| GET | `/astronauts/:astronautId/symptom-checkins` | `from`, `to`, `limit` |
| GET | `/astronauts/:astronautId/alerts` | `level`, `from`, `to`, `limit`; preserves uploaded evidence |
| GET | `/rules/latest` | Exact bundled bytes, ETag / If-None-Match |
| GET | `/guides/latest` | Exact bundled bytes, ETag / If-None-Match |
| POST | `/demo/seed` | Explicit synthetic load, optional replacement |
| GET | `/astronauts/:astronautId/trends` | Seven-day `TrendReport`, `utcOffsetMinutes` default 0 |
| GET | `/astronauts/:astronautId/overview` | **Proposed extension:** latest values, current rule status/evidence and stored counts |

History returns `{ "items": [...], "count": n }`, sorted by actual event time descending, with no `_id` or server metadata. `limit` defaults to 200 and is at most 1000. Measurement metrics: `heart_rate`, `blood_pressure`, `spo2`, `temperature`, `sleep`; alert levels: `warning`, `critical`. Both date bounds are inclusive UTC timestamps with seconds ending in `Z`, for example `from=2026-11-01T00:00:00Z&to=2026-11-13T23:59:59.999Z`. Missing bounds are open. Unsupported query keys, invalid dates/enums and reversed ranges return 400.

Trends include seven local-date points per field, nulls for missing days, baseline sample counts/mean/sample SD and evidence-backed insights. Overview recomputes current status from the latest records in the rules' context window; it does not infer current status from historical uploaded alerts. It does not save derived alerts. The Node implementation is parity-tested against the frozen Python reference fixture (`test/reference-parity.test.js`), including exact alert/insight UUIDv5 ids, evidence and metrics. Detailed math, interpretation choices and scope are in `docs/BACKEND-DECISIONS.md`.

## Test and package

```powershell
npm.cmd run contracts:verify
npm.cmd run test:unit
npm.cmd run test:reference
npm.cmd test
npm.cmd run package
```

`test:unit` needs no database. Full tests launch real disposable MongoDB with `mongodb-memory-server`; they **never use `MONGODB_URI`**. First use may download a large MongoDB binary and needs internet/write access to its cache. Alternatively set `MONGOMS_SYSTEM_BINARY` to a compatible installed `mongod` path. Tests cover HTTP, strict validation, replays/concurrency, acknowledgements, audits, history, seeding, vectors, analytics and real server restart persistence. Results and remaining limitations: `TEST-RESULTS.md`.

On Windows, `npm run package` produces `dist/astrocare-backend-v1.zip` with source, lockfile, tests, contracts, scripts and docs. It excludes `.env`, `node_modules`, database files, Git, and the archived prototype. On other platforms use any ZIP utility with the same inclusion list in `scripts/package.ps1`.

## Code layout

```text
src/app.js                 Express composition
src/server.js              Startup and shutdown
src/config.js, src/db.js    Configuration and database initialization
src/contracts.js           Immutable assets and JSON Schema / Zod adapters
src/models.js              Strict validated models and indexes
src/validation.js          Zod envelope/query and item checks
src/routes/                URL routing
src/controllers/           HTTP validation and responses
src/services/              Storage, sync, seed, rule evaluation and trends
src/middleware/            Authentication, tracing, readiness and errors
contracts/                 Unchanged shared v1 files
test/, scripts/, docs/     Checks, explicit seed, packaging and decisions
```

This is a single-astronaut educational demo. An API key embedded in an APK is not secure mobile authentication. Use synthetic data only until real user authentication, authorization and appropriate deployment controls are implemented. Illustrative alert thresholds and advice are not validated medical or flight policies. Backend tests establish contract and Python-fixture parity, not clinical suitability or on-device Android integration.
