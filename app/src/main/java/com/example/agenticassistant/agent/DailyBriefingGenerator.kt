package com.example.agenticassistant.agent

import com.example.agenticassistant.service.CalendarService

/**
 * Generates a daily briefing summary from today's calendar events.
 */
object DailyBriefingGenerator {

    /**
     * Fetches today's events and produces a spoken/readable summary.
     * @param calendarService An authenticated CalendarService instance.
     * @return A human-readable briefing string.
     */
    suspend fun generate(calendarService: CalendarService): String {
        val events = calendarService.getTodayEvents()

        if (events.isEmpty()) {
            return "You have no meetings scheduled for today. Your calendar is clear."
        }

        val sb = StringBuilder()
        sb.append("Today you have ${events.size} meeting${if (events.size > 1) "s" else ""}. ")
        events.forEachIndexed { index, event ->
            sb.append("${index + 1}. $event. ")
        }
        return sb.toString().trim()
    }
}

