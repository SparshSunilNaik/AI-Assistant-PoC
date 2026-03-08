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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
                AgentScreen(
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
                    onLogout = { logout() },
                    onScanInbox = { scanInbox() },
                    onScheduleMeeting = { scheduleMeeting() },
                    onDailyBriefing = { dailyBriefing() },
                    onVoiceCommand = { requestVoiceInput() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceCommandManager.destroy()
        assistantSpeaker.shutdown()
    }

    // ─── Logout ─────────────────────────────────────────────────

    private fun logout() {
        Log.d(TAG, "Logout button pressed")
        authManager.signOut {
            isSignedIn.value = false
            resultText.value = ""
            meetLink.value = ""
            extractedSlots.value = emptyList()
            extractedParticipants.value = emptyList()
            voiceStatus.value = ""
            isLoading.value = false
            isListening.value = false
            Log.d(TAG, "Logged out — state cleared")
        }
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
                smartScheduleMeeting()
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

    // ─── Smart Workflow Router ───────────────────────────────────

    private fun smartScheduleMeeting() {
        val credential = authManager.getCredential()
        if (credential == null) {
            meetLink.value = "Please sign in first."
            assistantSpeaker.speak("Please sign in first.")
            return
        }

        if (extractedSlots.value.isEmpty()) {
            assistantSpeaker.speak("Checking your inbox for meeting suggestions.")
            Log.d(TAG, "smartScheduleMeeting: No slots available, scanning inbox first")

            // Scan inbox first, then attempt scheduling once scan completes
            scanInboxThenSchedule()
        } else {
            assistantSpeaker.speak("Scheduling meeting using detected time.")
            Log.d(TAG, "smartScheduleMeeting: Using existing slot: ${extractedSlots.value.first()}")
            scheduleMeeting()
        }
    }

    /**
     * Scans the inbox and, once complete, automatically schedules a meeting
     * if slots were detected. Used by the smart workflow router.
     */
    private fun scanInboxThenSchedule() {
        val credential = authManager.getCredential() ?: return

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

                // Run local agent logic
                val summaries = EmailAnalyzer.summarize(emails)
                val meetingEmails = MeetingDetector.detectMeetingEmails(emails)
                val proposedSlots = TimeExtractor.extractTimesFromEmails(meetingEmails)
                val participants = ParticipantExtractor.extractFromEmails(meetingEmails)

                extractedSlots.value = proposedSlots
                extractedParticipants.value = participants

                // Build result display
                val sb = StringBuilder()
                sb.appendLine("📋 Summaries:")
                summaries.forEachIndexed { index, summary ->
                    sb.appendLine("${index + 1}. $summary")
                }

                if (meetingEmails.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("📅 Meeting Required!")
                    if (proposedSlots.isNotEmpty()) {
                        sb.appendLine("Proposed Slots:")
                        proposedSlots.forEach { sb.appendLine("  • $it") }
                    }
                    if (participants.isNotEmpty()) {
                        sb.appendLine("👥 Detected Participants:")
                        participants.forEach { sb.appendLine("  • $it") }
                    }
                }
                resultText.value = sb.toString()

                // Generate natural language explanation
                speakMeetingExplanation(meetingEmails, proposedSlots, participants)

                isLoading.value = false

                // Now attempt to schedule if we found slots
                if (proposedSlots.isNotEmpty()) {
                    assistantSpeaker.speak("Scheduling meeting using detected time.")
                    scheduleMeeting()
                } else {
                    assistantSpeaker.speak("I could not find a meeting time in your inbox.")
                }

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

                // Voice summary with natural language explanation
                val voiceSummary = "Inbox scanned. ${summaries.size} emails found." +
                    if (meetingRequired) " ${meetingEmails.size} emails suggest meetings." else ""
                assistantSpeaker.speak(voiceSummary)

                // Detailed explanation of detected meeting emails
                speakMeetingExplanation(meetingEmails, proposedSlots, participants)

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
                var startTimeMillis = resolveStartTime(extractedSlots.value.firstOrNull())
                val attendees = extractedParticipants.value

                // ── Calendar Conflict Detection ──
                Log.d(TAG, "Checking calendar conflicts for proposed time...")
                val freeSlots = calendarService.getFreeSlots(startTimeMillis)

                if (freeSlots.isNotEmpty()) {
                    val firstFreeSlotLabel = freeSlots.first()
                    val proposedSlotLabel = extractedSlots.value.firstOrNull()

                    // If the first free slot doesn't match our proposed time,
                    // the proposed time is busy — use the free slot instead
                    if (proposedSlotLabel != null && firstFreeSlotLabel != proposedSlotLabel) {
                        // Resolve to check: is our exact proposed time in the free list?
                        val proposedIsFree = freeSlots.any { it.equals(proposedSlotLabel, ignoreCase = true) }
                        if (!proposedIsFree) {
                            val conflictMsg = "That time is already booked. Your next available slot is $firstFreeSlotLabel."
                            Log.d(TAG, "Conflict detected: $conflictMsg")
                            assistantSpeaker.speak(conflictMsg)
                            resultText.value += "\n\n⚠️ $conflictMsg"

                            // Replace meeting time with the first free slot
                            startTimeMillis = resolveStartTime(firstFreeSlotLabel)
                            extractedSlots.value = listOf(firstFreeSlotLabel) + extractedSlots.value.drop(1)
                        }
                    }
                } else {
                    val noSlotsMsg = "No free slots found in the next 24 hours."
                    Log.w(TAG, noSlotsMsg)
                    assistantSpeaker.speak(noSlotsMsg)
                    meetLink.value = noSlotsMsg
                    return@launch
                }

                // ── Create the event ──
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

    // ─── Explanation Generation ──────────────────────────────────

    /**
     * Generates and speaks a natural-language explanation of detected meeting emails.
     * Uses sender info, extracted time slots, and participant emails to produce
     * human-friendly descriptions.
     */
    private fun speakMeetingExplanation(
        meetingEmails: List<com.example.agenticassistant.model.EmailMessage>,
        slots: List<String>,
        participants: List<String>
    ) {
        if (meetingEmails.isEmpty()) return

        val explanations = mutableListOf<String>()

        // Explain each meeting email with sender + time context
        for (email in meetingEmails) {
            val sender = email.from.let { from ->
                // Extract just the name or email part before the angle bracket
                val nameMatch = Regex("""^([^<]+)""").find(from)
                nameMatch?.groupValues?.get(1)?.trim()?.ifBlank { from } ?: from
            }

            val emailSlots = TimeExtractor.extractTimes("${email.subject} ${email.snippet}")
            val slotInfo = when {
                emailSlots.isNotEmpty() -> " suggesting ${emailSlots.first()}"
                slots.isNotEmpty() -> " suggesting ${slots.first()}"
                else -> ""
            }

            explanations.add("I found a meeting email from $sender$slotInfo.")
        }

        // Summarize participant info
        if (participants.isNotEmpty()) {
            val participantSummary = if (participants.size <= 3) {
                participants.joinToString(" and ")
            } else {
                "${participants.take(2).joinToString(", ")} and ${participants.size - 2} others"
            }
            explanations.add("I detected a meeting request involving $participantSummary.")
        }

        // Append explanation to result text
        val explanationBlock = "\n\n🤖 Assistant Insight:\n" + explanations.joinToString("\n")
        resultText.value += explanationBlock

        Log.d(TAG, "Meeting explanation: $explanations")
    }

    // ─── Daily Briefing ─────────────────────────────────────────

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

@OptIn(ExperimentalMaterial3Api::class)
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
    onLogout: () -> Unit = {},
    onScanInbox: () -> Unit = {},
    onScheduleMeeting: () -> Unit = {},
    onDailyBriefing: () -> Unit = {},
    onVoiceCommand: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Agentic Assistant",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (isSignedIn) {
                        IconButton(onClick = onLogout) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Logout",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Spacer(modifier = Modifier.height(4.dp))

            // ── Sign-In Card ──
            if (!isSignedIn) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Welcome",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Sign in to access your inbox, schedule meetings, and get daily briefings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onSignIn,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Sign in with Google")
                        }
                    }
                }
            }

            // ── Voice Command Card ──
            if (isSignedIn) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isListening)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onVoiceCommand,
                            enabled = !isListening && !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = if (isListening) "Listening…" else "Voice Command",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        AnimatedVisibility(
                            visible = voiceStatus.isNotEmpty(),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Text(
                                text = voiceStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ── Action Cards ──
            if (isSignedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionCard(
                        icon = Icons.Default.Email,
                        label = "Scan Inbox",
                        enabled = !isLoading,
                        onClick = onScanInbox,
                        modifier = Modifier.weight(1f)
                    )
                    ActionCard(
                        icon = Icons.Default.CalendarMonth,
                        label = "Schedule",
                        enabled = true,
                        onClick = onScheduleMeeting,
                        modifier = Modifier.weight(1f)
                    )
                }

                ActionCard(
                    icon = Icons.Default.QueryStats,
                    label = "Daily Briefing",
                    enabled = !isLoading,
                    onClick = onDailyBriefing,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Loading ──
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Results Card ──
            AnimatedVisibility(
                visible = resultText.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Split resultText into sections and render them
                        ResultSections(resultText = resultText)
                    }
                }
            }

            // ── Meeting Link Card ──
            AnimatedVisibility(
                visible = meetLink.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoCall,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Meeting Created!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                        Text(
                            text = meetLink,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDark)
                                com.example.agenticassistant.ui.theme.MeetLinkColorDark
                            else
                                com.example.agenticassistant.ui.theme.MeetLinkColor,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}


// ── Action Card Button ──────────────────────────────────────────

@Composable
fun ActionCard(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


// ── Result Section Renderer ─────────────────────────────────────

@Composable
fun ResultSections(resultText: String) {
    // Split on double newlines to detect logical sections
    val sections = resultText.split("\n\n").filter { it.isNotBlank() }

    sections.forEachIndexed { index, section ->
        val trimmed = section.trim()

        // Determine section icon based on content
        val (sectionIcon, sectionTitle, sectionBody) = parseSectionContent(trimmed)

        if (sectionTitle != null) {
            // Section heading
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (sectionIcon != null) {
                    Icon(
                        imageVector = sectionIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = sectionTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (sectionBody.isNotBlank()) {
                Text(
                    text = sectionBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 24.dp)
                )
            }
        } else {
            // Plain text block
            Text(
                text = trimmed,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }

        // Divider between sections (not after last)
        if (index < sections.lastIndex) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            )
        }
    }
}

/**
 * Parses a text section into (optional icon, optional title, body).
 * Recognises sections that start with emoji headings like "📋 Summaries:" etc.
 */
private fun parseSectionContent(text: String): Triple<ImageVector?, String?, String> {
    // Pattern: line starting with emoji + heading text, followed by body on subsequent lines
    val lines = text.lines()
    val firstLine = lines.firstOrNull()?.trim() ?: return Triple(null, null, text)

    // Detect known section patterns
    val (icon, title) = when {
        firstLine.contains("Summaries", ignoreCase = true) ->
            Icons.Default.Email to firstLine.replace(Regex("^[^A-Za-z]+"), "").trim()
        firstLine.contains("Meeting Required", ignoreCase = true) ||
        firstLine.contains("Scheduling", ignoreCase = true) ->
            Icons.Default.CalendarMonth to firstLine.replace(Regex("^[^A-Za-z]+"), "").trim()
        firstLine.contains("Daily Briefing", ignoreCase = true) ->
            Icons.Default.QueryStats to firstLine.replace(Regex("^[^A-Za-z]+"), "").trim()
        firstLine.contains("Assistant Insight", ignoreCase = true) ->
            Icons.Default.SmartToy to firstLine.replace(Regex("^[^A-Za-z]+"), "").trim()
        firstLine.contains("Participants", ignoreCase = true) ->
            null to firstLine.replace(Regex("^[^A-Za-z]+"), "").trim()
        firstLine.endsWith(":") && lines.size > 1 ->
            null to firstLine.replace(Regex("^[^A-Za-z]+"), "").trim()
        else -> return Triple(null, null, text)
    }

    val body = lines.drop(1).joinToString("\n").trim()
    return Triple(icon, title, body)
}


// ── Previews ────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Signed Out")
@Composable
fun AgentScreenPreviewSignedOut() {
    AgenticAssistantTheme(dynamicColor = false) {
        AgentScreen()
    }
}

@Preview(showBackground = true, name = "Signed In Light")
@Composable
fun AgentScreenPreviewSignedIn() {
    AgenticAssistantTheme(dynamicColor = false, darkTheme = false) {
        AgentScreen(
            isSignedIn = true,
            resultText = "📋 Summaries:\n1. Project sync — Let's discuss the roadmap…\n2. Budget review — Q2 numbers are ready…\n\n📅 Meeting Required!\nProposed Slots:\n  • Tomorrow at 10:30 AM\n\n🤖 Assistant Insight:\nI found a meeting email from Alice suggesting tomorrow at 10:30.",
            meetLink = "https://meet.google.com/abc-defg-hij"
        )
    }
}

@Preview(showBackground = true, name = "Signed In Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AgentScreenPreviewDark() {
    AgenticAssistantTheme(dynamicColor = false, darkTheme = true) {
        AgentScreen(
            isSignedIn = true,
            resultText = "📊 Daily Briefing:\n\nToday you have 2 meetings. 1. Project sync at 10:00 AM. 2. Research discussion at 2:00 PM."
        )
    }
}