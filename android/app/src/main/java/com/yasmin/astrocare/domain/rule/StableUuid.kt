package com.yasmin.astrocare.domain.rule

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Deterministic contract IDs shared with the AI/Data reference and the
 * backend (`src/services/rules.service.js`).
 *
 * Namespace and name layout are binding: the same alert must get the same id
 * on Android, in Python and in Node, otherwise the backend upsert stores it
 * twice. Verified against the reference fixture:
 * `v5("test") == 0565a3b2-100b-5ef6-ba25-fd0842c4fb01`.
 */
object StableUuid {

    val ALERT_NAMESPACE: UUID = UUID.fromString("6f5d1a0e-6f2c-4f4b-9a0f-1d7c2b3e4a55")

    /** RFC 4122 UUIDv5 (SHA-1 name-based) with version/variant bits set. */
    fun v5(name: String): String {
        val namespace = ByteBuffer.allocate(16)
            .putLong(ALERT_NAMESPACE.mostSignificantBits)
            .putLong(ALERT_NAMESPACE.leastSignificantBits)
            .array()
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(namespace + name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val msb = ByteBuffer.wrap(hash, 0, 8).long
        val lsb = ByteBuffer.wrap(hash, 8, 8).long
        return UUID(msb, lsb).toString()
    }

    /**
     * `alert|{astronautId}|{ruleId}|{revision}|{sorted measurementIds, sorted checkinIds}`
     * — exactly the name layout used by the Python reference and the backend.
     */
    fun alertId(
        astronautId: String,
        ruleId: String,
        revision: Int,
        measurementIds: List<String>,
        symptomCheckinIds: List<String>,
    ): String {
        val ids = measurementIds.sorted() + symptomCheckinIds.sorted()
        return v5("alert|$astronautId|$ruleId|$revision|${ids.joinToString(",")}")
    }
}
