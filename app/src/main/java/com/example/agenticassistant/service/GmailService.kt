package com.example.agenticassistant.service

import com.example.agenticassistant.model.EmailMessage
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GmailService(credential: GoogleAccountCredential) {

    private val gmail: Gmail = Gmail.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        credential
    )
        .setApplicationName("AgenticAssistant")
        .build()

    /**
     * Fetches the most recent emails from the user's inbox.
     *
     * @param maxResults Maximum number of messages to fetch (default 10).
     * @return List of EmailMessage with subject, snippet, from, and date.
     */
    suspend fun fetchRecentEmails(maxResults: Int = 10): List<EmailMessage> =
        withContext(Dispatchers.IO) {
            try {
                val messageList = gmail.users().messages()
                    .list("me")
                    .setMaxResults(maxResults.toLong())
                    .setLabelIds(listOf("INBOX"))
                    .execute()

                val messages = messageList.messages ?: return@withContext emptyList()

                messages.mapNotNull { msgRef ->
                    try {
                        val msg = gmail.users().messages()
                            .get("me", msgRef.id)
                            .setFormat("metadata")
                            .setMetadataHeaders(listOf("Subject", "From", "Date"))
                            .execute()

                        val headers = msg.payload?.headers ?: emptyList()
                        val subject = headers.find { it.name == "Subject" }?.value ?: "(no subject)"
                        val from = headers.find { it.name == "From" }?.value ?: "(unknown)"
                        val date = headers.find { it.name == "Date" }?.value ?: ""

                        EmailMessage(
                            id = msg.id,
                            subject = subject,
                            snippet = msg.snippet ?: "",
                            from = from,
                            date = date
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                throw e
            }
        }
}
