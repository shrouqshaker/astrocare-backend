package com.yasmin.astrocare.data.contract

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the PROVENANCE headers of every bundled contract file for Settings
 * (and the README): contract/schema/ruleSet/guideSet/protocol/seed versions,
 * the seed's synthetic-data label and the contract disclaimer.
 *
 * Offline: only `assets/contracts/` is read — never the network.
 */
@Singleton
class ProvenanceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    suspend fun load(): ContractProvenance = withContext(Dispatchers.IO) {
        val enums = header("contracts/enums.json")
        val rules = header("contracts/rules.v1.json")
        val guides = header("contracts/guides.v1.json")
        val protocol = header("contracts/chest-pain-protocol.v1.json")
        val seed = header("contracts/synthetic-seed.v1.json")
        ContractProvenance(
            contractVersion = enums.str("contractVersion"),
            schemaVersion = enums.str("schemaVersion"),
            disclaimer = enums.str("disclaimer"),
            ruleSetVersion = rules.str("ruleSetVersion"),
            ruleEffectiveFrom = rules.str("effectiveFrom"),
            guideSetVersion = guides.str("guideSetVersion"),
            protocolId = protocol.str("protocolId"),
            protocolVersion = protocol.str("protocolVersion"),
            seedVersion = seed.str("seedVersion"),
            seedLabel = seed.str("label"),
            seedGeneratedAt = seed.str("generatedAt"),
            seedEndDate = seed.str("endDate"),
            seedAstronautId = seed.str("astronautId"),
            seedEnvironment = seed.str("environment"),
        )
    }

    private fun header(path: String): JsonObject {
        val raw = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return json.parseToJsonElement(raw) as? JsonObject
            ?: error("$path is not a JSON object")
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""
}

/** Provenance display model — values are contract literals, nothing invented. */
data class ContractProvenance(
    val contractVersion: String,
    val schemaVersion: String,
    val disclaimer: String,
    val ruleSetVersion: String,
    val ruleEffectiveFrom: String,
    val guideSetVersion: String,
    val protocolId: String,
    val protocolVersion: String,
    val seedVersion: String,
    val seedLabel: String,
    val seedGeneratedAt: String,
    val seedEndDate: String,
    val seedAstronautId: String,
    val seedEnvironment: String,
)
