package com.yasmin.astrocare.core.common

/**
 * Demo configuration defaults.
 *
 * SECURITY: nothing here is a production secret. [DEFAULT_API_KEY] is the
 * hackathon demo key from the backend `.env.example` (`API_KEY=demo-key`);
 * `X-API-Key` is an explicitly demo-only convenience (see contracts/API.md
 * "Security note") and must never be treated as real authentication.
 * Both values are user-editable in Settings (Settings sync block, per
 * contracts/schemas/settings.schema.json) and are stored in local DataStore only.
 */
object AppDefaults {
    /** Android emulator loopback to the host machine (settings.schema.json example). */
    const val DEFAULT_BASE_URL = "https://backend-production-8ab773a0.up.railway.app/api/v1"

    /** DEMO ONLY — not a secret; see class doc. */
    const val DEFAULT_API_KEY = "demo-key"

    const val CONTRACT_VERSION = "1.0.0"
    const val SCHEMA_VERSION = "1.0"
    const val API_PREFIX = "/api/v1"

    const val DEFAULT_ASTRONAUT_ID = "demo-astronaut-01"

    /** Minimum sync interval allowed by settings.schema.json. */
    const val MIN_SYNC_INTERVAL_MINUTES = 15

    /** settings.schema.json default sync interval. */
    const val DEFAULT_SYNC_INTERVAL_MINUTES = 15

    /** `iss` — settings.schema.json / seed environment. */
    const val DEFAULT_ENVIRONMENT = "iss"

    /** Bundled rules.v1.json ruleSetVersion (docs/DECISIONS.md §6). */
    const val DEFAULT_RULE_SET_VERSION = "1.0.0"

    /** UI language for v1 (English only; schema also allows `ar`). */
    const val DEFAULT_LANGUAGE = "en"
}
