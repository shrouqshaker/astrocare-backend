# Compatibility decisions and proposed additions

The supplied `contracts/` files are copied byte-for-byte from `astrocare-contracts-v1.zip`. They have not been edited. `contracts-sha256.json` records the original hashes, checked by `npm run contracts:verify`.

The shared contract supersedes the earlier prototype: `/api/v1/sync/batch`, `id` (not `clientId`), measurements/symptom check-ins/alerts, `source: manual|synthetic`, and `created|duplicate|rejected`. No alias, field rename or migration is silently applied. The old source and documentation are preserved in `archive/pre-contract-v1/` in the workspace; they are excluded from the runnable project ZIP. Old MongoDB `readings`/`sleepentries`/`checkins` collections are left untouched and are not migrated.

## Explicit additions/substitution requiring team review

1. **Overview extension:** the user requested overview data, but the frozen API specifies no overview route or response schema. This backend adds `GET /api/v1/astronauts/:astronautId/overview` without modifying the shared files. Treat it as a proposed backend extension, not an already agreed shared endpoint. Response:

   ```text
   {
     schemaVersion: "1.0", astronautId, generatedAt,
     latestMeasurements: Measurement[],
     latestSymptomCheckin: SymptomCheckin | null,
     overallStatus: common.$defs.overallStatus,
     alerts: Alert[],
     counts: { measurements, symptomCheckins, alerts }
   }
   ```

   Latest values exclude future records. Status and generated alerts use the latest measurement per metric and latest symptom check-in within the 1,440-minute context window, following `rules.v1.json`. Current alerts include contract evidence and stable IDs; they are computed on demand and **not inserted** into uploaded alert history. Counts are stored-document totals. Stale latest measurements remain visible, but do not influence status. An empty database is `insufficient_data`, never healthy by default.

2. **Optional trends implementation:** the frozen API describes spawning `python -m astrocare_ai.cli trends`; the ZIP contains no such package or CLI input specification. The implemented endpoint computes trends directly in Node.js and returns a schema-valid `TrendReport` with `generatedBy: "backend_optional"`. `PYTHON_BIN` is reserved but unused. All five supplied aggregate vectors pass. The critical chest-pain demo is now parity-tested against the frozen Python reference fixture in `test/reference-parity.test.js`: alert and insight UUIDv5 ids, evidence, metrics and window match exactly. This is a validated Node implementation, not a claim that Python runs in production. See `docs/AI-DATA-INTEGRATION.md`.

3. **Additional timestamp validation:** shared schemas use regexes for many dates; Zod additionally rejects impossible calendar instants such as February 30. This tightens malformed-input handling without rewriting a schema. Offsets are not accepted for UTC fields; send `Z` and use the separate `utcOffsetMinutes` field.

## Choices for unspecified details

- History `from`/`to`: complete UTC timestamps ending in `Z`, inclusive on both sides; absent bounds are open. `count` is the number returned after `limit`, not a separate total. Sorting compares actual instants, handles fractional precision correctly, and breaks ties by `id`. No unsupported pagination token is invented.
- One demo astronaut only: `demo-astronaut-01`. Other well-formed identities return `404 NOT_FOUND`; a mismatching item inside a valid demo batch is individually rejected with `ASTRONAUT_MISMATCH`.
- Existing `(astronautId,id)` wins, even if valid incoming content differs. No `conflict` status is added because it is not in the final schema. UUID uniqueness is case-insensitive; stored JSON spelling is preserved. An alert acknowledgement transitions atomically from absent/null to its first non-null timestamp and cannot be reset or overwritten by a replay.
- An item with no string `id` is returned as `id: ""` with validation details, because the result schema requires a string and has no index/null property. Result arrays retain request order so Android can associate malformed items by position.
- Each received sync attempt adds an audit document (including replays). Reusing `batchId` does not cache a stale `created` response.
- Trend window: the current local date and six preceding dates at the requested fixed UTC offset; future observations are excluded. Daily points and week means use readings as samples. Direction compares equally weighted **unrounded daily means** for the earlier four vs last three dates, needs two populated dates on each side, and treats the stable-band boundary as stable.
- Baseline: observations in `[now - 14 days, now - 24 hours)`, at least five samples across three offset-local dates, mean and **sample** SD; display values are rounded to two decimals. `deltaVsBaseline` means latest minus baseline mean. Baseline deviation uses `abs(z) >= zThreshold` and `max(sampleSd, sdFloor)`; sleep insight uses the last three logged sleep records in the seven-day window. These interpretations are documented because full reference snapshots are absent.
- Healthy envelope plus invalid items returns HTTP 200 with per-item errors. A real storage failure cannot truthfully be encoded as any allowed item error; it returns a schema-shaped 500/503, possibly after partial writes. Retrying with the same IDs is safe. API.md lists 500 for sync; DB_UNAVAILABLE/503 is the operational outage response. No transaction or all-or-nothing batch guarantee is made.
- Explicit synthetic replacement deletes only `isSynthetic:true` records for the demo astronaut in the three data collections. Manual documents and audit logs survive. Run replacement as a maintenance/demo operation, not concurrently with writers; it is not transactional on standalone MongoDB.

## Scope

There are no standalone measurement-write, settings, guide-edit, chest-pain-evaluation or resource endpoints in the frozen API. Measurements/check-ins/alerts enter through sync. Guides, chest-pain protocol and resource fixtures remain available to Android under `contracts/data/`. Backend sync does not replace Android's offline rule evaluation or emergency guidance.

The API key is a local demo gate, not user authentication. Data and illustrative rules are supplied synthetic fixtures, not NASA clinical policies. Clinical validity, external dataset claims and production authentication are not verified by these backend tests.
