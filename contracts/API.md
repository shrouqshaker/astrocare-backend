# AstroCare API — Contract v1.0.0

Base path: /api/v1. Content-Type: application/json.
Auth: header X-API-Key: <key>, required everywhere except /health. A missing or wrong key returns 401 UNAUTHORIZED.
Tracing: the server returns X-Request-Id on every response, and the same value appears as error.requestId.
Timestamps: UTC ISO-8601 with Z.
Errors: the body always follows error.schema.json.

#	Method & path	Purpose	Success	Errors
1	GET /health	Liveness (no auth)	200 { "status":"ok","service":"astrocare-backend","contractVersion":"1.0.0","serverTime":"…","db":"up" }	503 DB_UNAVAILABLE
2	POST /sync/batch	Idempotent batch upsert	200 SyncBatchResponse (even when items are rejected)	400 VALIDATION_ERROR / UNSUPPORTED_SCHEMA_VERSION, 401, 413, 500
3	GET /astronauts/:astronautId/measurements?metric=&from=&to=&limit=	Read/restore history. limit default 200, max 1000, sorted by measuredAt desc	200 { "items":[Measurement], "count": n }	400, 401
4	GET /astronauts/:astronautId/symptom-checkins?from=&to=&limit=	Same pattern	200 { "items":[SymptomCheckin], "count": n }	400, 401
5	GET /astronauts/:astronautId/alerts?level=&from=&to=&limit=	Same pattern	200 { "items":[Alert], "count": n }	400, 401
6	GET /rules/latest	Serves rules.v1.json verbatim. Supports ETag / If-None-Match	200 rules JSON or 304	401, 404
7	GET /guides/latest	Serves guides.v1.json verbatim	200 GuideSet or 304	401, 404
8	POST /demo/seed body { "astronautId":"demo-astronaut-01","replace":false }	Loads synthetic-seed.v1.json into Mongo (idempotent by id). replace:true first deletes that astronaut’s synthetic docs	200 { "inserted":{ "measurements":n,"symptomCheckins":n }, "skipped":n }	400, 401
9	GET /astronauts/:astronautId/trends?utcOffsetMinutes=0 (P2, optional)	Spawns python -m astrocare_ai.cli trends on stored data. Returns TrendReport with generatedBy:"backend_optional". Skip if time is short.	200 TrendReport	401, 500

Sync semantics (the idempotency contract):

Validate the envelope against sync-request.schema.json minus the item arrays. If invalid, return 400 for the whole batch.
For each item, validate against its schema, check the BP cross-field rule, and check item.astronautId === envelope.astronautId. On failure, mark it rejected with an error and continue.
Upsert by {astronautId, id} with a unique compound index per collection. created means newly inserted, and duplicate means the id already existed with no change.
Exception: for a duplicate alert whose stored acknowledgedAt is null and whose incoming value is non-null, $set acknowledgedAt. The status is still duplicate.
Store a syncbatches audit document {batchId, deviceId, receivedAt, summary}. Replaying the same batch is safe and returns all duplicate.
Always return HTTP 200 with per-item results once the envelope is valid. Never use 207.

Mongo collections: measurements, symptomcheckins, alerts, syncbatches. Each stores the schema document unchanged plus _id and serverReceivedAt. Backend must keep unknown-field-free documents (strict mode).

Backend .env.example:

PORT=3000
MONGODB_URI=mongodb://localhost:27017/astrocare
API_KEY=demo-key
CONTRACTS_DIR=../contracts
PYTHON_BIN=python3
LOG_LEVEL=info

## Security note
`X-API-Key` is a local hackathon convenience, not secure mobile authentication. Never treat a key embedded in an APK as a secret or expose sensitive personal data with it.
