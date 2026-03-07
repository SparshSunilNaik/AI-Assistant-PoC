package com.example.agenticassistant.service

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.ConferenceData
import com.google.api.services.calendar.model.ConferenceSolutionKey
import com.google.api.services.calendar.model.CreateConferenceRequest
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventAttendee
import com.google.api.services.calendar.model.EventDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.util.UUID

data class MeetingResult(
    val meetLink: String,
    val eventId: String,
    val scheduledTime: String
)

class CalendarService(credential: GoogleAccountCredential) {

    private val calendar: Calendar = Calendar.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        credential
    )
        .setApplicationName("AgenticAssistant")
        .build()

    /**
     * Creates a Google Calendar event with a Google Meet link.
     *
     * @param title Event title.
     * @param startTimeMillis Event start time in epoch millis.
     * @param durationMinutes Duration in minutes (default 30).
     * @return MeetingResult containing the Meet link, event ID, and scheduled time.
     */
    suspend fun createMeetingEvent(
        title: String,
        startTimeMillis: Long,
        durationMinutes: Int = 30
    ): MeetingResult = withContext(Dispatchers.IO) {

        val timeZone = TimeZone.getDefault().id
        val startDateTime = DateTime(startTimeMillis)
        val endDateTime = DateTime(startTimeMillis + durationMinutes * 60 * 1000L)

        val event = Event().apply {
            summary = title
            start = EventDateTime().setDateTime(startDateTime).setTimeZone(timeZone)
            end = EventDateTime().setDateTime(endDateTime).setTimeZone(timeZone)
            conferenceData = ConferenceData().apply {
                createRequest = CreateConferenceRequest().apply {
                    requestId = UUID.randomUUID().toString()
                    conferenceSolutionKey = ConferenceSolutionKey().apply {
                        type = "hangoutsMeet"
                    }
                }
            }
        }

        val createdEvent = calendar.events()
            .insert("primary", event)
            .setConferenceDataVersion(1)
            .execute()

        val meetLink = createdEvent.conferenceData?.entryPoints
            ?.firstOrNull { it.entryPointType == "video" }
            ?.uri ?: createdEvent.htmlLink ?: ""

        MeetingResult(
            meetLink = meetLink,
            eventId = createdEvent.id ?: "",
            scheduledTime = createdEvent.start?.dateTime?.toString() ?: ""
        )
    }

    /**
     * Creates a Google Calendar event with attendees and a Google Meet link.
     */
    suspend fun createMeetingEventWithAttendees(
        title: String,
        startTimeMillis: Long,
        durationMinutes: Int = 30,
        attendeeEmails: List<String> = emptyList()
    ): MeetingResult = withContext(Dispatchers.IO) {

        val timeZone = TimeZone.getDefault().id
        val startDateTime = DateTime(startTimeMillis)
        val endDateTime = DateTime(startTimeMillis + durationMinutes * 60 * 1000L)

        val event = Event().apply {
            summary = title
            start = EventDateTime().setDateTime(startDateTime).setTimeZone(timeZone)
            end = EventDateTime().setDateTime(endDateTime).setTimeZone(timeZone)
            if (attendeeEmails.isNotEmpty()) {
                attendees = attendeeEmails.map { email ->
                    EventAttendee().setEmail(email)
                }
            }
            conferenceData = ConferenceData().apply {
                createRequest = CreateConferenceRequest().apply {
                    requestId = UUID.randomUUID().toString()
                    conferenceSolutionKey = ConferenceSolutionKey().apply {
                        type = "hangoutsMeet"
                    }
                }
            }
        }

        val createdEvent = calendar.events()
            .insert("primary", event)
            .setConferenceDataVersion(1)
            .setSendUpdates("all")
            .execute()

        val meetLink = createdEvent.conferenceData?.entryPoints
            ?.firstOrNull { it.entryPointType == "video" }
            ?.uri ?: createdEvent.htmlLink ?: ""

        MeetingResult(
            meetLink = meetLink,
            eventId = createdEvent.id ?: "",
            scheduledTime = createdEvent.start?.dateTime?.toString() ?: ""
        )
    }

    /**
     * Fetches today's calendar events.
     * @return List of event summaries with times.
     */
    suspend fun getTodayEvents(): List<String> = withContext(Dispatchers.IO) {
        val now = java.util.Calendar.getInstance()
        now.set(java.util.Calendar.HOUR_OF_DAY, 0)
        now.set(java.util.Calendar.MINUTE, 0)
        now.set(java.util.Calendar.SECOND, 0)
        now.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = DateTime(now.timeInMillis)

        now.set(java.util.Calendar.HOUR_OF_DAY, 23)
        now.set(java.util.Calendar.MINUTE, 59)
        now.set(java.util.Calendar.SECOND, 59)
        val endOfDay = DateTime(now.timeInMillis)

        val events = calendar.events()
            .list("primary")
            .setTimeMin(startOfDay)
            .setTimeMax(endOfDay)
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute()

        events.items?.map { event ->
            val time = event.start?.dateTime?.let { dt ->
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = dt.value
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = cal.get(java.util.Calendar.MINUTE)
                val amPm = if (hour < 12) "AM" else "PM"
                val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                String.format(java.util.Locale.US, "%d:%02d %s", displayHour, minute, amPm)
            } ?: "All day"
            val title = event.summary ?: "(No title)"
            "$title at $time"
        } ?: emptyList()
    }

    /**
     * Checks for free slots around a requested time.
     * If the requested time conflicts, suggests nearby alternatives.
     * @param requestedTimeMillis The desired meeting start time.
     * @param durationMinutes Duration of the meeting.
     * @return List of free slot strings (e.g., "5:30 PM", "6:00 PM", "Tomorrow 10:00 AM").
     */
    suspend fun getFreeSlots(
        requestedTimeMillis: Long,
        durationMinutes: Int = 30
    ): List<String> = withContext(Dispatchers.IO) {
        // Check a 24-hour window around the requested time
        val windowStart = DateTime(requestedTimeMillis - 2 * 3600_000L)
        val windowEnd = DateTime(requestedTimeMillis + 24 * 3600_000L)

        val busyEvents = calendar.events()
            .list("primary")
            .setTimeMin(windowStart)
            .setTimeMax(windowEnd)
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute()

        // Collect busy intervals as pairs of (startMillis, endMillis)
        val busyIntervals = busyEvents.items?.mapNotNull { event ->
            val start = event.start?.dateTime?.value ?: return@mapNotNull null
            val end = event.end?.dateTime?.value ?: return@mapNotNull null
            start to end
        } ?: emptyList()

        val meetingDuration = durationMinutes * 60_000L
        val slots = mutableListOf<String>()

        // Check half-hour slots from the requested time forward over 24 hours
        var candidate = requestedTimeMillis
        val limit = requestedTimeMillis + 24 * 3600_000L
        while (candidate < limit && slots.size < 5) {
            val candidateEnd = candidate + meetingDuration
            val isFree = busyIntervals.none { (busyStart, busyEnd) ->
                candidate < busyEnd && candidateEnd > busyStart
            }
            if (isFree) {
                slots.add(formatTimeSlot(candidate))
            }
            candidate += 30 * 60_000L // advance by 30 minutes
        }

        slots
    }

    private fun formatTimeSlot(millis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis

        val today = java.util.Calendar.getInstance()
        val isToday = cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) &&
                cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)

        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val timeStr = String.format(java.util.Locale.US, "%d:%02d %s", displayHour, minute, amPm)

        return if (isToday) timeStr else "Tomorrow $timeStr"
    }
}

