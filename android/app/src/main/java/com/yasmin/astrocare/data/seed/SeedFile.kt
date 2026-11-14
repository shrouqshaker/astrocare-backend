package com.yasmin.astrocare.data.seed

import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.MeasurementDto
import com.yasmin.astrocare.data.remote.dto.SymptomCheckInDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * Parsed shape of the frozen demo seed `contracts/data/synthetic-seed.v1.json`
 * (bundled at `assets/contracts/synthetic-seed.v1.json`).
 *
 * The seed rows are contract objects byte-for-byte — timestamps, ids and values are
 * imported VERBATIM with their original values; nothing is regenerated or shifted
 * to "today" (docs/DECISIONS.md §1).
 */
@Serializable
internal data class SeedFile(
    val schemaVersion: String,
    val seedVersion: String,
    val generatedAt: String,
    /** "SYNTHETIC DEMO DATA – NOT REAL ASTRONAUT DATA" — surfaced in Settings/README. */
    val label: String,
    val astronautId: String,
    val environment: String,
    val days: Int,
    /** Seed ends on this date (2026-11-13); the gap to "today" is intentional. */
    val endDate: String,
    val rngSeed: Int,
    val measurements: List<MeasurementDto> = emptyList(),
    val symptomCheckins: List<SymptomCheckInDto> = emptyList(),
    val alerts: List<AlertDto> = emptyList(),
)

/** Parse the raw seed JSON with the app's shared [kotlinx.serialization.json.Json]. */
internal fun parseSeedFile(json: kotlinx.serialization.json.Json, raw: String): SeedFile =
    json.decodeFromString(raw)
