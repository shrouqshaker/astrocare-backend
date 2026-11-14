package com.yasmin.astrocare.core.common

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Contract `utcDateTime` helpers (`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$`).
 * Available on minSdk 26 (java.time).
 */
object IsoTime {

    /** Parses a contract UTC timestamp to epoch millis; throws on anything else. */
    fun parseUtc(iso: String): Long = try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("not a contract utcDateTime: '$iso'", e)
    }

    /** Epoch millis to contract UTC timestamp (millisecond precision, Z suffix). */
    fun formatUtc(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
