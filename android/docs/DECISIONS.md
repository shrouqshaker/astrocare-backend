# AstroCare Android — Architecture decisions (Phase 1–2)

Source: Phase 1 contract audit + reviewer feedback before Phase 2. These decisions are
binding for later phases. Contract files are never modified by the Android track.

## 1. Synthetic seed and trends

- The seed (`contracts/data/synthetic-seed.v1.json`) is bundled **byte-for-byte unchanged**
  and imported with its original timestamps (2026-10-31 … 2026-11-13). Rewriting dates is
  a contract modification and is forbidden.
- 7-day trend charts are computed from **actual Room timestamps** relative to "now".
  Days with no readings are rendered as **"No data"** — no interpolation, no gap filling,
  no fabricated readings, ever.
- Synthetic rows are always labeled (`source="synthetic"`, `isSynthetic=true`, seed label
  "SYNTHETIC DEMO DATA — NOT REAL ASTRONAUT DATA") and must be visually distinguished
  from manual readings in Overview and Trends.
- Known gap: the seed ends 2026-11-13 (docs/OPEN_ISSUES.md #4). The current window may
  therefore show fewer than 7 days with data. Documented in README + Settings, not "fixed".

## 2. Emergency and alert semantics

- **No hardcoded alert levels anywhere.** `level` always comes from the rule that fires
  (`rules.v1.json → rules[].level`), never from UI code or from the triage outcome.
- `chest-pain-protocol.v1.json → outcomeRule` states both outcomes keep immediate safety
  instructions visible **and** the alert level critical — this refers to the
  `SYM-CHEST-C` rule (level `critical`, category `emergency`,
  `emergencyProtocolId: "chest-pain-v1"`), which fires only when a check-in containing
  `chest_pain` is **saved and evaluated by the rule engine**.
- `symptom-checkin.schema.json → chestPainTriage.outcome` (`emergency_now` |
  `urgent_contact`) is **data stored on the check-in**, not an alert and not an alert level.
- Opening the emergency screen alone creates **no alert**. Immediate safety guidance is
  shown first, before any question, with no network/LLM wait. Max 5 questions (CP-Q1…CP-Q5),
  outcome computed exactly per protocol (`emergency_now` if any of CP-Q1..CP-Q4 is
  `yes`/`unsure`, else `urgent_contact`).
- Alert schema allows only `warning` | `critical`; `normal` / `insufficient_data` are
  overall statuses only.

## 3. Synchronization reliability (POST /api/v1/sync/batch)

- **Item ids:** a UUID (`id`) is generated **once** when the row is inserted into Room and
  never regenerated. Room primary key = contract `id`; insert uses `OnConflictStrategy.IGNORE`
  so a re-insert of the same id can never duplicate or overwrite local data.
  There is no `clientId` anywhere.
- **batchId:** derived deterministically from the ordered set of pending item ids
  (UUIDv5-style name-based UUID over `astronautId + sorted "collection:id"` list).
  Retrying the same pending set produces the **same batchId** (stable across retries and
  process restarts); the set changing produces a new batchId (it is a new batch).
  `sentAt` reflects the current attempt.
- **Per-item results:** each `created`/`duplicate` → `localSyncStatus = "synced"`;
  `rejected` → `localSyncStatus = "rejected"` plus the returned `error` (code/message/details)
  stored in an Android-only column for inspection. Rejected rows are **never auto-retried**
  (permanent validation errors) and are excluded from future batches.
- **Envelope-level failure** (network error, 400/401/413/500): no row state changes; all
  items stay `pending`; WorkManager backoff retries; `lastSyncResult = "failed"`.
  A partially rejected response sets `lastSyncResult = "partial"`.
- **Idempotency / data loss:** sync **never deletes** local rows. Replaying an identical
  batch is expected to return all `duplicate` (backend upserts by `(astronautId, id)`).
  Alerts are deduped locally before insert (same `ruleId` + identical `triggeredBy` → no new
  alert) per `rules.v1.json → evaluation.dedupe`.
- `localSyncStatus` is Android-only and is **never** serialized into any payload.

## 4. API configuration

- Base URL and API key live in local settings (DataStore), user-editable in Settings,
  schema-shaped per `settings.schema.json → sync`.
- Defaults: the deployed demo backend (`https://backend-production-…railway.app/api/v1`)
  and `demo-key` (backend `.env.example`). Both are **demo-only** values, labeled as such in
  the UI and README — never production secrets, never logged. A host-only URL
  (`http://10.0.2.2:3000`) is equally valid.
- **Endpoint resolution:** `SyncClient.endpointUrl` strips a trailing `/api/v1` before
  appending `/api/v1/sync/batch`, so both a host-only URL and a URL that already includes
  the prefix resolve to the same endpoint — the prefix is never duplicated.
- No request logging of measurement payloads. `X-API-Key` handling follows the
  API.md "Security note": demo convenience, not authentication.

## 5. Dependency compatibility (spike)

- Spike runs **before** feature code: KSP 2.2.10-2.0.2, Room 2.8.5, Hilt 2.60.1,
  kotlinx-serialization **1.11.0** (+ serialization plugin 2.2.10), Retrofit 2.12.0 +
  official `converter-kotlinx-serialization` 2.12.0 (working API in this build:
  `json.asConverterFactory(mediaType)` extension, package
  `retrofit2.converter.kotlinx.serialization`; the `KotlinSerializationConverterFactory`
  class form failed to resolve under this toolchain — use the extension), OkHttp 4.12.1, WorkManager 2.12.0,
  DataStore 1.3.0, Navigation-Compose 2.10.2, material-icons-core 1.7.8 (BOM) —
  verified with a real `assembleDebug testDebugUnitTest` build under
  AGP 9.3.0 / Gradle 9.5.0 / Kotlin 2.2.10 (built-in Kotlin). Results in the Phase 2 summary.
- **Kotlin metadata ceiling (the spike's key finding):** AGP 9.3.0's built-in compiler is
  Kotlin 2.2.10 and can read class metadata **up to 2.3.0**. Any dependency compiled with
  Kotlin ≥2.4 (metadata 2.4.0) fails the build with
  `Module was compiled with an incompatible version of Kotlin`. Therefore:
  - kotlinx-serialization pinned to **1.11.0** (declares kotlin-stdlib 2.3.20 → metadata
    2.3.0 ✓). **1.12.0/1.13.0 are forbidden** — they declare kotlin-stdlib 2.4.10 ✗.
  - Resolved kotlin-stdlib = **2.3.21** (metadata 2.3.0 ✓ readable).
  - kotlinx-coroutines main-classpath resolves to 1.9.0 (AndroidX-driven ✓).
- AGP 9 built-in Kotlin also forbids KSP's `kotlin.sourceSets` registration →
  `android.disallowKotlinSourceSets=false` added to `gradle.properties` (official flag).
- compileSdk = **37.1** (user-resolved): several 2026-era AARs (core 1.19.1,
  lifecycle 2.11.0, …) require compileSdk ≥ 37; targetSdk stays 36.
- Room rejects `entities = []` → temporary `ToolchainProbeEntity` registers the schema
  until Phase 3 swaps in the real entities (see `core/database/AstroCareDatabase.kt`).
- Fallback ladder if Hilt/KSP fail: kapt → manual `AppContainer` DI (Room stays mandatory).

## 6. Testing gate

- `contracts/test-vectors/rule-vectors.v1.json` contains **34 vectors** (count verified).
  They are copied unmodified into `app/src/test/resources/` and become mandatory
  acceptance tests: **the Rule Engine phase (Phase 4) does not proceed until all 34 pass**.
- `trend-vectors.v1.json` (5 aggregate fixtures) are unit tests for trend math.
  Full TrendReport parity remains flagged as unverified (docs/OPEN_ISSUES.md #3).

## 7. Device illustrations

- Five original illustrations generated locally by `scripts/GenerateGuideAssets.java`
  (no downloaded assets), written to `app/src/main/assets/` with the exact base names
  required by `guides.v1.json → deviceIllustration.assetName`:
  `guide_heart_rate`, `guide_blood_pressure`, `guide_spo2`, `guide_temperature`,
  `guide_sleep` (stored as `.png`; the loader resolves `assetName + ".png"`).
- Contents verified programmatically against the guide `altText` descriptions
  (pulse monitor, arm cuff + monitor, finger oximeter, thermometer, sleep diary).

## 8. Seed import & sync status of demo rows (Phase 3)

- The frozen seed is copied byte-for-byte into
  `app/src/main/assets/contracts/synthetic-seed.v1.json` and imported verbatim on
  first launch: original ids, ISO-8601 timestamps (2026-10-31 → 2026-11-13), values
  and `label` unchanged; idempotent via `count() > 0` + `INSERT OR IGNORE`.
- Imported rows get Android-only `localSyncStatus = "synced"`: the backend ships the
  same frozen seed with the same ids, so demo rows are never uploaded (also keeps the
  first real sync batch small). User-created rows start `"pending"`.
- Room `version = 2` with destructive migration from v1: v1 held only the Phase-2
  `toolchain_probe` shell table (since deleted); no released installs exist, so no
  1→2 data migration is written (documented in README "Known limitations").
- Contract timestamps are stored as their original UTC ISO-8601 strings (not epoch):
  guarantees byte-exact round-trips, and canonical `...Z` strings sort chronologically,
  which the DAO window queries rely on.

## 9. Rule engine interpretations (Phase 4)

Binding interpretations where `rules.v1.json` / schemas leave room, verified
against `rule-vectors.v1.json` (all 34 green):

- **Alert list ordering**: the contract defines no alert-list order. Output is
  sorted by `ruleId` (matches the vectors' layout); acceptance tests compare
  `(ruleId, level)` as sets.
- **"Valid" measurement** (contextPolicy says "latest valid measurement" but
  never defines valid): a row is valid iff every present value lies inside the
  enums.json `min`/`max` for its field, is finite, and the metric's
  `crossFieldRule` (systolic > diastolic) holds; empty rows and unknown metrics
  are invalid. Invalid rows are skipped and the next-latest valid row is used.
- **Context window boundary** is inclusive: a reading exactly
  `contextWindowMinutes` old is still in context; `+1 min` is out.
- **Status source**: `critical > warning > insufficient_data > normal` is
  computed from group-selected candidates BEFORE dedupe — dedupe concerns
  stored history and must not change status; `firingRuleIds` are those
  candidates' ids (sorted). Insights never affect status.
- **Group ties**: if two firing rules share a group and level, the first in
  rules-file order wins (deterministic; current ruleset never produces a tie).
- **Missing data**: a `when.field` leaf whose field is absent, or a `when.symptom`
  leaf absent from the latest in-window check-in, is FALSE (no fire, no error).
- **Baseline-deviation evidence** (contract silent on comparator/threshold for
  insights): `observed` = latest value, `threshold` = rounded baseline mean,
  `comparator` = `>`/`<` by deviation direction, plus `baselineMean`,
  `baselineSd`, `zScore` (2-decimal rounding). Fires when
  `|z| >= zThreshold` with `effectiveSd = max(sampleSD, sdFloor)`.
- **window_average window**: mean of the newest `lastN` readings of the field
  within the trend baseline 14-day window, INCLUDING the last 24h ("last N
  logged"); fewer than `lastN` readings => not evaluated + diagnostic.
- **Explanation units** render through enums.json `unitDisplay` ("percent" ->
  "%"); `evidence.unit` keeps the raw schema unit. An unresolvable placeholder
  stays literal in the output (visible defect, never invented text).
- **Evaluation budget**: exceeding `maxEvaluationMillis` records a diagnostic;
  it never fails or truncates the evaluation.
- **Layering note**: `domain.rule` references the contract DTOs
  (`data.remote.dto`) directly — they are pure kotlinx.serialization shapes
  with no I/O (Room/Retrofit never appear in the engine). Deliberate: the
  contract is the shared language of both layers.

## 10. UI layer decisions (Phase 5)

- **Alert persistence trigger**: alerts are written ONLY by the save path
  (`evaluation.trigger = on_save_of_measurement_or_symptom_checkin`).
  Overview runs `evaluateForDisplay()` — same engine, same policies — which
  never persists alerts; it only surfaces status/insights/diagnostics.
- **Trend math** (TrendCalculator, `trendPolicy` verbatim): window = 7
  inclusive local days ending today at the device offset; `weekMean` averages
  days WITH data (gaps are "No data" — never zero-filled); direction = mean
  of the last 3 days vs the earlier 4, per-field `stableBand`, both sides need
  `minDaysWithDataPerSide` days with data else `insufficient_data`; per-field
  baseline via `BaselineCalculator` (14d window minus last 24h), metric-level
  baseline pooled across the metric's fields with the rejection reason
  surfaced (`baseline.reason`) — never silent.
- **TrendReport provenance**: `schemaVersion` comes from enums.json via
  `ContractCatalog.schemaVersion`; `generatedBy = android_local`;
  `ruleSetVersion` from rules.v1.json; insights may be an empty array
  (schema-valid) because Trends never runs an evaluation.
- **Integer contract fields** serialize as JSON integers (`JsonPrimitive(value.toLong())`)
  so stored `values` objects match `type: integer` in enums.json.
- **`utcOffsetMinutes`** is the DEVICE zone offset at the current instant
  (measurements, check-ins and trend windows alike) — never `ZoneOffset.UTC`.
- **Chest-pain triage**: outcome computed from the data
  (`yesIsRedFlag` && answer != no -> `emergency_now`, else `urgent_contact`);
  stored ON the check-in as `chestPainTriage` data, never as an alert level
  (§2). The emergency screen always shows the safety instructions first and
  keeps them visible in every step; if the check-in write fails the locally
  computed outcome is still shown with the failure surfaced alongside it.
  Symptom-rule alert levels still come from rules.v1.json only.
- **Environments** (`iss | spacecraft | moon_base`) are parsed from enums.json
  into `ContractCatalog.environments` — the Settings picker and emergency
  contact lookup are contract-driven, never hardcoded.
- **Entry-time validation**: `MeasurementValidator` applies the SAME contract
  rules as `ContextBuilder` (min/max, integer type, crossFieldRule) so an
  invalid row can never reach Room; Overview "latest readings" renders
  blood pressure as `120/80 mmHg` and sleep as `7.4 h · 4/5` from
  enums.json units only.
- **Save pipeline UI**: validate -> Room insert -> `evaluateAndPersist()` ->
  surface overall status, new alert titles/levels and the measured elapsed
  time; diagnostics from the engine are shown on Overview (never dropped).

## Toolchain notes (AGP 9)

- AGP 9.3.0 uses built-in Kotlin (no `kotlin-android` plugin applied by the template).
- Plugins added: `org.jetbrains.kotlin.plugin.serialization` (2.2.10),
  `com.google.devtools.ksp` (2.2.10-2.0.2), `com.google.dagger.hilt.android` (2.60.1).
- `Room.database` schema: v1 was the Phase-2 shell (`ToolchainProbeEntity`, since
  deleted); **v2** defines the real contract tables — measurements, symptom_checkins,
  alerts — with Android-only `localSyncStatus`/`syncError` columns (Phase 3).

## 11. Post-implementation fixes and test coverage (2026-11-14)

- **Recurring reminders:** `setExactAndAllowWhileIdle` is one-shot, so
  `ReminderReceiver` now re-arms the schedule (`ReminderScheduler.apply`) after posting
  each notification. Relying on `Application.onCreate` alone was insufficient for a warm
  process. `BootReceiver` additionally restores alarms on `TIME_SET` and
  `TIMEZONE_CHANGED` (manifest intent-filter updated), because wall-clock alarms shift
  when the system clock moves.
- **Dead Retrofit provider removed:** `NetworkModule` now provides only `Json` and
  `OkHttpClient`. The only Retrofit instance is the dynamic-base one inside `SyncClient`;
  a fixed-base provider silently ignored the Settings base URL.
- **Rejected rows inspection:** Settings lists permanently rejected rows (collection,
  label, id, stored `CODE: message`). The list is read-only and observed from Room;
  rejected rows remain excluded from `getPending`.
- **Backend compatibility (2026-11-14):** the backend repo
  (`shrouqshaker/astrocare-backend`) ships byte-identical `contracts/` assets. Three
  sync/parity gaps were found and fixed: (1) `SyncItemsDto` empty arrays were omitted by
  kotlinx `encodeDefaults=false`, while the envelope schema REQUIRES all three arrays —
  `@EncodeDefault` now keeps them; (2) alert ids were random UUIDs while the AI/Data
  Python reference and Node backend use UUIDv5
  (`alert|astronaut|rule|revision|sortedIds`, namespace `6f5d1a0e-…`) — `StableUuid`
  now matches both; (3) acknowledging an already-synced alert left it `synced`, so the
  backend never received `acknowledgedAt` — `AlertDao.acknowledge` now re-queues synced
  alerts (`rejected` rows stay rejected).
- **JVM test suite expanded:** pure-JVM tests for `NextTriggerCalculator` (incl. DST),
  `SyncBatchPlanner`, `SyncResultPlanner`, `SyncEngine` (fake DAOs + transport) and
  `SyncClient.endpointUrl`; Robolectric integration tests for real Room schema/DAOs, seed
  import, rejected-row observation, WorkManager scheduling and `SyncWorker`; Robolectric
  Compose UI tests for Overview states and the safety-first emergency flow. The UI gained
  stateless `OverviewContent` / `EmergencyContent` entry points to make those tests
  possible without Hilt.
