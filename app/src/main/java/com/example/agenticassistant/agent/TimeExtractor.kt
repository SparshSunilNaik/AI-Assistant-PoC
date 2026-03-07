package com.example.agenticassistant.agent

object TimeExtractor {

    // Matches patterns like "5 pm", "10 am", "5:30 pm", "2:00 PM"
    private val TIME_PATTERN =
        Regex("""\b(\d{1,2}(:\d{2})?\s*(am|pm|AM|PM|a\.m\.|p\.m\.))\b""")

    // Matches patterns like "tomorrow at 4", "tomorrow at 10:30"
    private val TOMORROW_PATTERN =
        Regex("""\btomorrow\s+at\s+(\d{1,2}(:\d{2})?(\s*(am|pm|AM|PM))?)\b""", RegexOption.IGNORE_CASE)

    // Matches patterns like "Monday at 3 pm", "Tuesday 10:30 am"
    private val DAY_TIME_PATTERN =
        Regex("""\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+(at\s+)?(\d{1,2}(:\d{2})?\s*(am|pm|AM|PM)?)\b""", RegexOption.IGNORE_CASE)

    /**
     * Extracts all time references from the given text.
     * Returns a list of human-readable time slot strings.
     */
    fun extractTimes(text: String): List<String> {
        val results = mutableSetOf<String>()

        // Extract "tomorrow at X" patterns
        TOMORROW_PATTERN.findAll(text).forEach { match ->
            results.add("Tomorrow at ${match.groupValues[1].trim()}")
        }

        // Extract "DayName at X" patterns
        DAY_TIME_PATTERN.findAll(text).forEach { match ->
            val day = match.groupValues[1].replaceFirstChar { it.uppercase() }
            val time = match.groupValues[3].trim()
            results.add("$day $time")
        }

        // Extract standalone time patterns (only if not already captured in context)
        TIME_PATTERN.findAll(text).forEach { match ->
            val time = match.groupValues[1].trim()
            // Avoid duplicates from day-based matches
            if (results.none { it.endsWith(time, ignoreCase = true) }) {
                results.add(time)
            }
        }

        return results.toList()
    }

    /**
     * Extracts times from a list of email texts (subject + snippet).
     */
    fun extractTimesFromEmails(emails: List<com.example.agenticassistant.model.EmailMessage>): List<String> {
        val allTimes = mutableSetOf<String>()
        for (email in emails) {
            val combined = "${email.subject} ${email.snippet}"
            allTimes.addAll(extractTimes(combined))
        }
        return allTimes.toList()
    }
}

