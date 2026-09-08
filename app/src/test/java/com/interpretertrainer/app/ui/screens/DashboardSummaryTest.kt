package com.interpretertrainer.app.ui.screens

import com.interpretertrainer.app.data.database.PracticeSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DashboardSummaryTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun countsOnlyCompletedSessionsFromTheCurrentMondayBasedWeek() {
        val previousSunday = session(startedAt = "2026-09-06T23:59:59Z")
        val monday = session(startedAt = "2026-09-07T00:00:00Z")
        val tuesday = session(startedAt = "2026-09-08T09:00:00Z")
        val incomplete = session(startedAt = "2026-09-08T10:00:00Z", status = "IN_PROGRESS")

        val summary = buildDashboardSummary(
            sessions = listOf(previousSunday, monday, tuesday, incomplete),
            nowMillis = Instant.parse("2026-09-08T12:00:00Z").toEpochMilli(),
            zoneId = utc
        )

        assertEquals(2, summary.completedThisWeek)
        assertEquals(6, summary.weeklyGoal)
        assertEquals(2f / 6f, summary.progress, 0.0001f)
        assertSame(tuesday, summary.latestSession)
    }

    @Test
    fun capsProgressWhenTheGoalIsExceeded() {
        val sessions = (0 until 8).map { index ->
            session(startedAt = "2026-09-0${7 + index / 4}T0${index % 4}:00:00Z")
        }

        val summary = buildDashboardSummary(
            sessions = sessions,
            nowMillis = Instant.parse("2026-09-08T12:00:00Z").toEpochMilli(),
            zoneId = utc,
            weeklyGoal = 4
        )

        assertEquals(8, summary.completedThisWeek)
        assertEquals(1f, summary.progress, 0f)
    }

    @Test
    fun rejectsAnInvalidWeeklyGoal() {
        assertThrows(IllegalArgumentException::class.java) {
            buildDashboardSummary(emptyList(), weeklyGoal = 0)
        }
    }

    private fun session(
        startedAt: String,
        status: String = "COMPLETED"
    ) = PracticeSessionEntity(
        practiceMode = "SIMULTANEOUS_INTERPRETATION",
        sourceLanguage = "en-US",
        targetLanguage = "ar-MA",
        startedAt = Instant.parse(startedAt).toEpochMilli(),
        durationMillis = 12 * 60 * 1_000L,
        sourceName = "General Conference",
        transcript = "",
        notes = "",
        segmentDurationSeconds = null,
        status = status
    )
}
