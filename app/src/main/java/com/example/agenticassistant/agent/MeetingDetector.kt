package com.example.agenticassistant.agent

import com.example.agenticassistant.model.EmailMessage

object MeetingDetector {

    private val MEETING_KEYWORDS = listOf(
        "meeting", "discuss", "call", "sync", "schedule", "agenda",
        "catch up", "standup", "stand-up", "huddle", "conference"
    )

    /**
     * Returns true if any email in the list contains meeting-related language.
     */
    fun isMeetingRequired(emails: List<EmailMessage>): Boolean {
        return emails.any { email ->
            val text = "${email.subject} ${email.snippet}".lowercase()
            MEETING_KEYWORDS.any { keyword -> text.contains(keyword) }
        }
    }

    /**
     * Filters and returns only those emails that contain meeting-related keywords.
     */
    fun detectMeetingEmails(emails: List<EmailMessage>): List<EmailMessage> {
        return emails.filter { email ->
            val text = "${email.subject} ${email.snippet}".lowercase()
            MEETING_KEYWORDS.any { keyword -> text.contains(keyword) }
        }
    }
}

