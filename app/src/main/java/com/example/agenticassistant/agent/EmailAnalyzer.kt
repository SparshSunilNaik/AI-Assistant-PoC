package com.example.agenticassistant.agent

import com.example.agenticassistant.model.EmailMessage

object EmailAnalyzer {

    /**
     * Produces a one-line summary for each email from subject + snippet.
     */
    fun summarize(emails: List<EmailMessage>): List<String> {
        return emails.map { email ->
            val subject = email.subject.ifBlank { "(no subject)" }
            val snippet = email.snippet.take(120).let {
                if (email.snippet.length > 120) "$it…" else it
            }
            "$subject — $snippet"
        }
    }
}

