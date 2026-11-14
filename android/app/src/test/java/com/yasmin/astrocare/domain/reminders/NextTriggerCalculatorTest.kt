package com.yasmin.astrocare.domain.reminders

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [NextTriggerCalculator] (no Android APIs).
 *
 * The calculator is the single source of truth for when an AlarmManager alarm
 * fires AND for the re-arm after each fire (ReminderReceiver). A regression
 * here means reminders fire late, early, or not at all.
 */
class NextTriggerCalculatorTest {

    private val utc = ZoneOffset.UTC
    private val allDays = listOf(1, 2, 3, 4, 5, 6, 7)

    private fun millis(text: String, zone: ZoneId = utc): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun local(epochMillis: Long, zone: ZoneId = utc): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    @Test
    fun `fires later today when the time has not passed yet`() {
        val now = millis("2026-11-14T06:00")
        val next = NextTriggerCalculator.nextTrigger(now, "08:00", allDays, utc)!!
        assertEquals(LocalDateTime.parse("2026-11-14T08:00"), local(next))
    }

    @Test
    fun `is strictly after now - same minute rolls to the next scheduled day`() {
        val now = millis("2026-11-14T08:00")
        val next = NextTriggerCalculator.nextTrigger(now, "08:00", allDays, utc)!!
        assertEquals(LocalDateTime.parse("2026-11-15T08:00"), local(next))
        assertEquals(true, next > now)
    }

    @Test
    fun `skips days that are not in the schedule`() {
        // 2026-11-14 is a Saturday; schedule Monday only.
        val now = millis("2026-11-14T12:00")
        val next = NextTriggerCalculator.nextTrigger(now, "07:30", listOf(1), utc)!!
        val at = local(next)
        assertEquals(DayOfWeek.MONDAY, at.dayOfWeek)
        assertEquals(LocalDateTime.parse("2026-11-16T07:30"), at)
    }

    @Test
    fun `wraps around the week when today's slot already passed`() {
        // Friday 09:00 passed, schedule Friday only -> next Friday.
        val now = millis("2026-11-13T09:00")
        val next = NextTriggerCalculator.nextTrigger(now, "09:00", listOf(5), utc)!!
        assertEquals(LocalDateTime.parse("2026-11-20T09:00"), local(next))
    }

    @Test
    fun `multiple days picks the earliest upcoming day`() {
        val now = millis("2026-11-14T06:00") // Saturday
        val next = NextTriggerCalculator.nextTrigger(now, "08:00", listOf(1, 7), utc)!!
        assertEquals(LocalDateTime.parse("2026-11-15T08:00"), local(next)) // Sunday
    }

    @Test
    fun `DST spring-forward gap resolves forward to a real instant`() {
        // US DST starts 2026-03-08; 02:30 does not exist in New York.
        val zone = ZoneId.of("America/New_York")
        val now = millis("2026-03-07T12:00", zone)
        val next = NextTriggerCalculator.nextTrigger(now, "02:30", allDays, zone)!!
        val at = Instant.ofEpochMilli(next).atZone(zone)
        assertEquals(LocalDateTime.parse("2026-03-08T03:30"), at.toLocalDateTime())
        assertEquals(true, next > now)
    }

    @Test
    fun `DST fall-back overlap resolves to the earlier offset`() {
        // US DST ends 2026-11-01; 01:30 occurs twice in New York.
        val zone = ZoneId.of("America/New_York")
        val now = millis("2026-10-31T12:00", zone)
        val next = NextTriggerCalculator.nextTrigger(now, "01:30", allDays, zone)!!
        // Earlier offset = EDT (-04:00) -> 05:30Z.
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun `malformed schedules return null instead of throwing`() {
        val now = millis("2026-11-14T06:00")
        assertNull(NextTriggerCalculator.nextTrigger(now, "7:00", allDays, utc))
        assertNull(NextTriggerCalculator.nextTrigger(now, "24:00", allDays, utc))
        assertNull(NextTriggerCalculator.nextTrigger(now, "08:60", allDays, utc))
        assertNull(NextTriggerCalculator.nextTrigger(now, "", allDays, utc))
        assertNull(NextTriggerCalculator.nextTrigger(now, "08:00", emptyList(), utc))
        assertNull(NextTriggerCalculator.nextTrigger(now, "08:00", listOf(0, 3), utc))
        assertNull(NextTriggerCalculator.nextTrigger(now, "08:00", listOf(8), utc))
    }

    @Test
    fun `isoDays returns sorted ISO day numbers`() {
        assertEquals(
            listOf(1, 3, 7),
            NextTriggerCalculator.isoDays(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        )
    }
}
