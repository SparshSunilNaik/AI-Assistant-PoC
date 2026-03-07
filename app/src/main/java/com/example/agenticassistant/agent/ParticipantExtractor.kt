package com.example.agenticassistant.agent

import com.example.agenticassistant.model.EmailMessage

/**
 * Extracts participant email addresses from email content.
 */
object ParticipantExtractor {

    private val EMAIL_REGEX =
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

    /**
     * Extracts all unique email addresses from a single email's subject, snippet, and from fields.
     */
    fun extractFromEmail(email: EmailMessage): List<String> {
        val combined = "${email.from} ${email.subject} ${email.snippet}"
        return EMAIL_REGEX.findAll(combined)
            .map { it.value.lowercase() }
            .distinct()
            .toList()
    }

    /**
     * Extracts all unique participant email addresses from a list of emails.
     */
    fun extractFromEmails(emails: List<EmailMessage>): List<String> {
        return emails.flatMap { extractFromEmail(it) }
            .distinct()
    }
}

