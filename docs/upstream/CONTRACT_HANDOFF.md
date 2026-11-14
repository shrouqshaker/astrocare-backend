# AstroCare — Track handoff

**Freeze:** contractVersion `1.0.0`, schemaVersion `1.0`. Do not rename fields or change endpoints in a track chat without a 3-person agreement.

## Android developer (`android/`)
- Read `contracts/enums.json` and measurement/checkin/alert/trend/settings schemas.
- Bundle `contracts/data/{rules,guides,chest-pain-protocol,resources}.v1.json` into Android assets. Add 5 device illustrations with names specified in guide JSON.
- Use Room as offline source of truth; `localSyncStatus` is Android-only.
- Evaluate rules locally and match `test-vectors/rule-vectors.v1.json`; never wait for API before safety information.
- Implement 4 tabs, AlarmManager reminders, WorkManager sync to `/api/v1/sync/batch`, and 7-day trends.

## Backend developer (`backend/`)
- Implement `contracts/API.md` exactly; prefix `/api/v1`.
- Use JSON Schemas under `contracts/schemas` with Ajv 2020-12 and `ajv-formats`.
- Upsert by unique compound `(astronautId,id)`; preserve per-item rejected/duplicate behavior.
- Serve `/rules/latest`, `/guides/latest`; `X-API-Key` for demo only.

## AI/Data developer (`ai-data/`)
- Own / review `contracts/data`, 14-day synthetic seed, rules, guides, source manifest, reference interpreter, tests and vectors.
- Test Python/Kotlin rule parity, source provenance, schema compatibility, trends and guide consistency.
- DO NOT claim demo thresholds are medical guidance or represent flight-certified policies.

## Integration
1. Copy **exact same** `contracts/` into all three workspaces.
2. Android saves a reading and local alert offline.
3. WorkManager sends a batch and handles `created`, `duplicate`, `rejected`.
4. Re-send identical batch and verify duplicate statuses.
5. Verify 7-day charts, measurement guides, chest pain offline and environment-dependent guidance.
6. Use synthetic seed to demo; show provenance in Settings/README.

## Contract details
- API base: `/api/v1`, not `/api`.
- IDs: `id` (UUID), not `clientId`; never silently rename.
- Alert level: `warning|critical`; `normal|insufficient_data` are overall status only.
- Use `contracts/` not `shared/`; Python track is `ai-data/`, not `ai/`.
- Rule interpreter grammar supports `when.field/op/value`, `when.symptom`, `when.all`, `when.any`, and insight-specific types.
