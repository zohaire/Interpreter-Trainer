package com.interpretertrainer.app.ui.screens

import com.interpretertrainer.app.data.database.PracticeSessionEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal const val DEFAULT_WEEKLY_GOAL = 6

internal data class DashboardSummary(
    val completedThisWeek: Int,
    val weeklyGoal: Int,
    val progress: Float,
    val latestSession: PracticeSessionEntity?
)

internal fun buildDashboardSummary(
    sessions: List<PracticeSessionEntity>,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    weeklyGoal: Int = DEFAULT_WEEKLY_GOAL
): DashboardSummary {
    require(weeklyGoal > 0) { "Weekly goal must be greater than zero" }

    val currentDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val weekStartDate = currentDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekStart = weekStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val nextWeekStart = weekStartDate.plusWeeks(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    val completedThisWeek = sessions.count { session ->
        session.status.equals("COMPLETED", ignoreCase = true) &&
            session.startedAt >= weekStart &&
            session.startedAt < nextWeekStart
    }
    val progress = (completedThisWeek.toFloat() / weeklyGoal).coerceIn(0f, 1f)

    return DashboardSummary(
        completedThisWeek = completedThisWeek,
        weeklyGoal = weeklyGoal,
        progress = progress,
        latestSession = sessions
            .asSequence()
            .filter { it.status.equals("COMPLETED", ignoreCase = true) }
            .maxByOrNull(PracticeSessionEntity::startedAt)
    )
}
