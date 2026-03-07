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
}

