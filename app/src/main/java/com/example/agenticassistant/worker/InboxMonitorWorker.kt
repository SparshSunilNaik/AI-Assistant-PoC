package com.example.agenticassistant.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.agenticassistant.R
import com.example.agenticassistant.agent.MeetingDetector
import com.example.agenticassistant.auth.GoogleAuthManager
import com.example.agenticassistant.service.GmailService

/**
 * Background worker that periodically checks Gmail for meeting requests
 * and shows a notification if one is detected.
 */
class InboxMonitorWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "InboxMonitorWorker"
        const val WORK_NAME = "inbox_monitor_work"
        private const val CHANNEL_ID = "inbox_monitor_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "InboxMonitorWorker started")

        val authManager = GoogleAuthManager(appContext)
        val credential = authManager.getCredential()
        if (credential == null) {
            Log.w(TAG, "User not signed in — skipping inbox check")
            return Result.success()
        }

        return try {
            val gmailService = GmailService(credential)
            val emails = gmailService.fetchRecentEmails(5)

            if (emails.isEmpty()) {
                Log.d(TAG, "No new emails found")
                return Result.success()
            }

            val meetingDetected = MeetingDetector.isMeetingRequired(emails)
            if (meetingDetected) {
                val meetingEmails = MeetingDetector.detectMeetingEmails(emails)
                val count = meetingEmails.size
                Log.d(TAG, "Meeting request detected in $count email(s)")
                showNotification(
                    "Meeting request detected",
                    "$count email${if (count > 1) "s" else ""} suggest a meeting. Open the app to review."
                )
            } else {
                Log.d(TAG, "No meeting-related emails found")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking inbox: ${e.message}", e)
            Result.retry()
        }
    }

    private fun showNotification(title: String, body: String) {
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required for API 26+, which is our minSdk)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Inbox Monitor",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when meeting requests are detected in your inbox"
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}

