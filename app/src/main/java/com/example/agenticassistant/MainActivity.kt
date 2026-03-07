package com.example.agenticassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.agenticassistant.agent.DailyBriefingGenerator
import com.example.agenticassistant.agent.EmailAnalyzer
import com.example.agenticassistant.agent.MeetingDetector
import com.example.agenticassistant.agent.ParticipantExtractor
import com.example.agenticassistant.agent.TimeExtractor
import com.example.agenticassistant.auth.GoogleAuthManager
import com.example.agenticassistant.service.CalendarService
import com.example.agenticassistant.service.GmailService
import com.example.agenticassistant.ui.theme.AgenticAssistantTheme
import com.example.agenticassistant.voice.AssistantSpeaker
import com.example.agenticassistant.voice.VoiceCommandManager
import com.example.agenticassistant.worker.InboxMonitorWorker
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var authManager: GoogleAuthManager
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var assistantSpeaker: AssistantSpeaker

    // Mutable state exposed to Compose
    private val isSignedIn = mutableStateOf(false)
    private val extractedSlots = mutableStateOf<List<String>>(emptyList())
    private val extractedParticipants = mutableStateOf<List<String>>(emptyList())
    private val resultText = mutableStateOf("")
    private val meetLink = mutableStateOf("")
    private val isLoading = mutableStateOf(false)
    private val isListening = mutableStateOf(false)
    private val voiceStatus = mutableStateOf("")

    // Sign-in launcher (preserved from original)
    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "Sign-in result received. ResultCode: ${result.resultCode}")
            val account = authManager.handleSignInResult(result.data)
            if (account != null) {
                isSignedIn.value = true
                Log.d(TAG, "✅ Sign-in successful: ${account.email}")
                Toast.makeText(
                    this,
                    "Signed in as ${account.email ?: "unknown"}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                isSignedIn.value = false
                val errorMsg = authManager.getSignInError(result.data)
                Log.e(TAG, "❌ Sign-in failed: $errorMsg")
                Toast.makeText(this, "Sign-in failed: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }

    // Consent launcher (preserved from original)
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            Toast.makeText(this, "Permission granted. Please try again.", Toast.LENGTH_SHORT).show()
        }

    // RECORD_AUDIO permission launcher
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d(TAG, "RECORD_AUDIO permission granted")
                startVoiceListening()
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied")
                Toast.makeText(this, "Microphone permission is required for voice commands.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = GoogleAuthManager(this)
        voiceCommandManager = VoiceCommandManager(this)
        assistantSpeaker = AssistantSpeaker(this)
        isSignedIn.value = authManager.isSignedIn()
        Log.d(TAG, "onCreate: isSignedIn = ${isSignedIn.value}")

        // Schedule background inbox monitoring
        scheduleInboxMonitor()

        enableEdgeToEdge()

        setContent {
            AgenticAssistantTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AgentScreen(
                        modifier = Modifier.padding(innerPadding),
                        isSignedIn = isSignedIn.value,
                        resultText = resultText.value,
                        meetLink = meetLink.value,
                        isLoading = isLoading.value,
                        isListening = isListening.value,
                        voiceStatus = voiceStatus.value,
                        onSignIn = {
                            Log.d(TAG, "Sign-in button pressed")
                            authManager.signOut {
                                signInLauncher.launch(authManager.getSignInIntent())
                            }
                        },
                        onScanInbox = { scanInbox() },
                        onScheduleMeeting = { scheduleMeeting() },
                        onDailyBriefing = { dailyBriefing() },
                        onVoiceCommand = { requestVoiceInput() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceCommandManager.destroy()
        assistantSpeaker.shutdown()
    }

    // ─── Voice Input ──────────────────────────────────────────────

    private fun requestVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceListening()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceListening() {
        isListening.value = true
        voiceStatus.value = "🎤 Listening..."
        voiceCommandManager.startListening(
            onResult = { rawText, command ->
                isListening.value = false
                voiceStatus.value = "Heard: \"$rawText\""
                Log.d(TAG, "Voice command: $rawText → $command")
                routeVoiceCommand(command, rawText)
            },
            onError = { errorMsg ->
                isListening.value = false
                voiceStatus.value = "⚠️ $errorMsg"
                assistantSpeaker.speak(errorMsg)
            }
        )
    }

    private fun routeVoiceCommand(command: VoiceCommandManager.Command, rawText: String) {
        when (command) {
            VoiceCommandManager.Command.SCAN_INBOX -> {
                assistantSpeaker.speak("Scanning your inbox now.")
                scanInbox()
            }
            VoiceCommandManager.Command.SCHEDULE_MEETING -> {
                assistantSpeaker.speak("Scheduling a meeting.")
                scheduleMeeting()
            }
            VoiceCommandManager.Command.DAILY_BRIEFING -> {
                assistantSpeaker.speak("Getting your daily briefing.")
                dailyBriefing()
            }
            VoiceCommandManager.Command.UNKNOWN -> {
                val msg = "I didn't understand \"$rawText\". Try saying check inbox, schedule meeting, or daily briefing."
                voiceStatus.value = msg
                assistantSpeaker.speak(msg)
            }
        }
    }

    // ─── Scan Inbox (preserved + extended with participants) ──────

    private fun scanInbox() {
        val credential = authManager.getCredential()
        if (credential == null) {
            resultText.value = "Please sign in first."
            assistantSpeaker.speak("Please sign in first.")
            return
        }

        isLoading.value = true
        resultText.value = ""
        meetLink.value = ""

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val gmailService = GmailService(credential)
                val emails = gmailService.fetchRecentEmails(10)

                if (emails.isEmpty()) {
                    resultText.value = "No emails found."
                    assistantSpeaker.speak("No emails found in your inbox.")
                    isLoading.value = false
                    return@launch
                }

                // Run local agent logic (all original logic preserved)
                val summaries = EmailAnalyzer.summarize(emails)
                val meetingRequired = MeetingDetector.isMeetingRequired(emails)
                val meetingEmails = MeetingDetector.detectMeetingEmails(emails)
                val proposedSlots = TimeExtractor.extractTimesFromEmails(meetingEmails)

                // NEW: Extract participants from meeting emails
                val participants = ParticipantExtractor.extractFromEmails(meetingEmails)

                extractedSlots.value = proposedSlots
                extractedParticipants.value = participants

                val sb = StringBuilder()

                sb.appendLine("📋 Summaries:")
                summaries.forEachIndexed { index, summary ->
                    sb.appendLine("${index + 1}. $summary")
                }

                if (meetingRequired) {
                    sb.appendLine()
                    sb.appendLine("📅 Meeting Required!")
                    if (proposedSlots.isNotEmpty()) {
                        sb.appendLine("Proposed Slots:")
                        proposedSlots.forEach { sb.appendLine("  • $it") }
                    } else {
                        sb.appendLine("No specific times found in emails.")
                    }
                    if (participants.isNotEmpty()) {
                        sb.appendLine()
                        sb.appendLine("👥 Detected Participants:")
                        participants.forEach { sb.appendLine("  • $it") }
                    }
                }

                resultText.value = sb.toString()

                // Voice summary
                val voiceSummary = "Inbox scanned. ${summaries.size} emails found." +
                    if (meetingRequired) " ${meetingEmails.size} emails suggest meetings." else ""
                assistantSpeaker.speak(voiceSummary)

            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
                resultText.value = "Additional permissions required. Please try again."
            } catch (e: Exception) {
                resultText.value = "Error: ${e.message}"
                assistantSpeaker.speak("An error occurred while scanning your inbox.")
            } finally {
                isLoading.value = false
            }
        }
    }

    // ─── Schedule Meeting (preserved + extended with attendees) ───

    private fun scheduleMeeting() {
        val credential = authManager.getCredential()
        if (credential == null) {
            meetLink.value = "Please sign in first."
            assistantSpeaker.speak("Please sign in first.")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val calendarService = CalendarService(credential)
                val startTimeMillis = resolveStartTime(extractedSlots.value.firstOrNull())
                val attendees = extractedParticipants.value

                val result = if (attendees.isNotEmpty()) {
                    calendarService.createMeetingEventWithAttendees(
                        title = "Meeting via AgenticAssistant",
                        startTimeMillis = startTimeMillis,
                        attendeeEmails = attendees
                    )
                } else {
                    calendarService.createMeetingEvent(
                        title = "Meeting via AgenticAssistant",
                        startTimeMillis = startTimeMillis
                    )
                }

                meetLink.value = result.meetLink
                val attendeeInfo = if (attendees.isNotEmpty()) " with ${attendees.size} participants" else ""
                assistantSpeaker.speak("Meeting scheduled successfully$attendeeInfo.")

            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
                meetLink.value = "Additional permissions required. Please try again."
            } catch (e: Exception) {
                meetLink.value = "Failed to create meeting: ${e.message}"
                assistantSpeaker.speak("Failed to create the meeting.")
            }
        }
    }

    // ─── Daily Briefing (new) ─────────────────────────────────────

    private fun dailyBriefing() {
        val credential = authManager.getCredential()
        if (credential == null) {
            resultText.value = "Please sign in first."
            assistantSpeaker.speak("Please sign in first.")
            return
        }

        isLoading.value = true
        resultText.value = ""

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val calendarService = CalendarService(credential)
                val briefing = DailyBriefingGenerator.generate(calendarService)
                resultText.value = "📊 Daily Briefing:\n\n$briefing"
                assistantSpeaker.speak(briefing)

            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
                resultText.value = "Additional permissions required. Please try again."
            } catch (e: Exception) {
                resultText.value = "Error getting briefing: ${e.message}"
                assistantSpeaker.speak("An error occurred while getting your daily briefing.")
            } finally {
                isLoading.value = false
            }
        }
    }

    // ─── Background Worker ────────────────────────────────────────

    private fun scheduleInboxMonitor() {
        val workRequest = PeriodicWorkRequestBuilder<InboxMonitorWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            InboxMonitorWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "Inbox monitor worker scheduled")
    }

    // ─── Time Resolution (preserved from original) ────────────────

    private fun resolveStartTime(slot: String?): Long {
        if (slot == null) {
            return System.currentTimeMillis() + 3600_000L
        }

        return try {
            val cal = Calendar.getInstance()
            val lower = slot.lowercase().trim()

            if (lower.startsWith("tomorrow")) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val timePart = lower.removePrefix("tomorrow").removePrefix("at").trim()
                applyTimeToCal(cal, timePart)
                return cal.timeInMillis
            }

            val days = mapOf(
                "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
                "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
                "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
                "sunday" to Calendar.SUNDAY
            )
            for ((name, dayConst) in days) {
                if (lower.startsWith(name)) {
                    val timePart = lower.removePrefix(name).trim()
                    val today = cal.get(Calendar.DAY_OF_WEEK)
                    var diff = dayConst - today
                    if (diff <= 0) diff += 7
                    cal.add(Calendar.DAY_OF_YEAR, diff)
                    applyTimeToCal(cal, timePart)
                    return cal.timeInMillis
                }
            }

            applyTimeToCal(cal, lower)
            if (cal.timeInMillis < System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            cal.timeInMillis
        } catch (e: Exception) {
            System.currentTimeMillis() + 3600_000L
        }
    }

    private fun applyTimeToCal(cal: Calendar, timePart: String) {
        val cleaned = timePart.replace(".", "").trim()
        val isPm = cleaned.contains("pm", ignoreCase = true)
        val isAm = cleaned.contains("am", ignoreCase = true)
        val numericPart = cleaned.replace(Regex("[^0-9:]"), "").trim()

        val parts = numericPart.split(":")
        var hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0

        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }
}


// ═══════════════════════════════════════════════════════════════════
// Compose UI
// ═══════════════════════════════════════════════════════════════════

@Composable
fun AgentScreen(
    modifier: Modifier = Modifier,
    isSignedIn: Boolean = false,
    resultText: String = "",
    meetLink: String = "",
    isLoading: Boolean = false,
    isListening: Boolean = false,
    voiceStatus: String = "",
    onSignIn: () -> Unit = {},
    onScanInbox: () -> Unit = {},
    onScheduleMeeting: () -> Unit = {},
    onDailyBriefing: () -> Unit = {},
    onVoiceCommand: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {

        Spacer(modifier = Modifier.height(24.dp))

        // ── Sign-In ──
        if (!isSignedIn) {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with Google")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Voice Command Button ──
        Button(
            onClick = onVoiceCommand,
            enabled = isSignedIn && !isListening && !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isListening) "🎤 Listening..." else "🎤 Voice Command")
        }

        if (voiceStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = voiceStatus,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Action Buttons Row 1 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onScanInbox,
                enabled = !isLoading && isSignedIn,
                modifier = Modifier.weight(1f)
            ) {
                Text("📥 Scan Inbox")
            }
            Button(
                onClick = onScheduleMeeting,
                enabled = isSignedIn,
                modifier = Modifier.weight(1f)
            ) {
                Text("📅 Schedule")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Action Buttons Row 2 ──
        Button(
            onClick = onDailyBriefing,
            enabled = !isLoading && isSignedIn,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📊 Daily Briefing")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Loading Indicator ──
        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Results ──
        if (resultText.isNotEmpty()) {
            Text(
                text = resultText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = Color.Black
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Meeting Link ──
        if (meetLink.isNotEmpty()) {
            Text(
                text = "Meeting Created!",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = meetLink,
                color = Color.Blue,
                fontSize = 16.sp
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
fun AgentScreenPreview() {
    AgenticAssistantTheme {
        AgentScreen()
    }

}