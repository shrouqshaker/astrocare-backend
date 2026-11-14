package com.yasmin.astrocare.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display formatting for contract values — presentation only; the stored data
 * stays byte-exact. Numbers are shown without a trailing ".0" so integers read
 * naturally while decimals keep their precision.
 */
object Format {

    /** "72.0" style trimming: 72.0 -> "72"; 97.857 stays "97.857". */
    fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /** "2026-11-13T18:00:00Z" -> "Nov 13, 18:00" in the device timezone. */
    fun shortDateTime(isoUtc: String): String = try {
        val instant = Instant.parse(isoUtc)
        LOCAL_DATE_TIME.format(instant.atZone(ZoneId.systemDefault()))
    } catch (e: Exception) {
        isoUtc
    }

    /** Epoch millis + UTC offset -> contract `localDate` ("2026-11-13"). */
    fun localDate(millis: Long, utcOffsetMinutes: Int): String =
        java.time.Instant.ofEpochMilli(millis + utcOffsetMinutes * 60_000L)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()

    /** "2026-11-13" -> "Nov 13" for chart axis labels. */
    fun dayLabel(localDate: String): String = try {
        DAY_LABEL.format(java.time.LocalDate.parse(localDate))
    } catch (e: Exception) {
        localDate
    }

    private val LOCAL_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)

    private val DAY_LABEL: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
}
