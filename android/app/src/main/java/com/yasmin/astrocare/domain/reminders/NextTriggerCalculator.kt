package com.yasmin.astrocare.domain.reminders

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Pure next-fire-time math for measurement reminders.
 *
 * Contract (settings.schema.json): `time` is `HH:mm`, `daysOfWeek` holds ISO
 * day numbers (1=Monday … 7=Sunday, at least one, unique). The next trigger
 * is the first instant STRICTLY AFTER `nowMillis` in [zone] whose local time
 * matches `time` on a scheduled day.
 *
 * DST is handled by java.time: nonexistent local times (spring-forward gap)
 * resolve forward, ambiguous times (fall-back) resolve to the earlier offset —
 * both yield a valid epoch millis. No Android APIs: unit-testable on the JVM.
 */
object NextTriggerCalculator {

    private val TIME_PATTERN = Regex("""^(?:[01]\d|2[0-3]):[0-5]\d$""")

    /** Returns epoch millis of the next fire, or null for a malformed schedule. */
    fun nextTrigger(
        nowMillis: Long,
        time: String,
        daysOfWeek: List<Int>,
        zone: ZoneId,
    ): Long? {
        val parsed = parseTime(time) ?: return null
        if (daysOfWeek.isEmpty()) return null
        val scheduledDays = daysOfWeek.toSet()
        if (scheduledDays.any { it !in 1..7 }) return null

        var day = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        // Worst case: today + a full week ahead (any day maps within 7 days).
        repeat(8) {
            if (day.dayOfWeek.value in scheduledDays) {
                val candidate = day.atTime(parsed.first, parsed.second).atZone(zone)
                val millis = candidate.toInstant().toEpochMilli()
                if (millis > nowMillis) return millis
            }
            day = day.plusDays(1)
        }
        return null
    }

    /** Contract pattern `^([01]\d|2[0-3]):[0-5]\d$` — null when it does not match. */
    private fun parseTime(time: String): Pair<Int, Int>? {
        val trimmed = time.trim()
        if (!TIME_PATTERN.matches(trimmed)) return null
        val parts = trimmed.split(":")
        return parts[0].toInt() to parts[1].toInt()
    }

    /** Convenience for UI/tests: ISO day numbers of a set of [DayOfWeek]. */
    fun isoDays(vararg days: DayOfWeek): List<Int> = days.map { it.value }.sorted()
}
