package com.example.agenticassistant

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agenticassistant.agent.EmailAnalyzer
import com.example.agenticassistant.agent.MeetingDetector
import com.example.agenticassistant.agent.TimeExtractor
import com.example.agenticassistant.auth.GoogleAuthManager
import com.example.agenticassistant.service.CalendarService
import com.example.agenticassistant.service.GmailService
import com.example.agenticassistant.ui.theme.AgenticAssistantTheme
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var authManager: GoogleAuthManager

    // Mutable state exposed to Compose
    private val isSignedIn = mutableStateOf(false)

    // Keep extracted slots so "Schedule Meeting" can use the first one
    private val extractedSlots = mutableStateOf<List<String>>(emptyList())

    // Sign-in launcher
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
                Toast.makeText(
                    this,
                    "Sign-in failed: $errorMsg",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // Launcher for handling UserRecoverableAuthIOException (consent screen)
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            // After consent, user can retry
            Toast.makeText(this, "Permission granted. Please try again.", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = GoogleAuthManager(this)
        isSignedIn.value = authManager.isSignedIn()
        Log.d(TAG, "onCreate: isSignedIn = ${isSignedIn.value}")

        enableEdgeToEdge()

        setContent {
            AgenticAssistantTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AgentScreen(
                        modifier = Modifier.padding(innerPadding),
                        isSignedIn = isSignedIn.value,
                        onSignIn = {
                            Log.d(TAG, "Sign-in button pressed — clearing stale state first")
                            // Sign out first to clear any stale cached sign-in state,
                            // then launch a fresh sign-in flow
                            authManager.signOut {
                                signInLauncher.launch(authManager.getSignInIntent())
                            }
                        },
                        onScanInbox = { setResult, setLoading -> scanInbox(setResult, setLoading) },
                        onScheduleMeeting = { setMeetLink -> scheduleMeeting(setMeetLink) },
                        extractedSlots = extractedSlots.value
                    )
                }
            }
        }
    }

    private fun scanInbox(
        setResult: (String) -> Unit,
        setLoading: (Boolean) -> Unit
    ) {
        val credential = authManager.getCredential()
        if (credential == null) {
            setResult("Please sign in first.")
            return
        }

        setLoading(true)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val gmailService = GmailService(credential)
                val emails = gmailService.fetchRecentEmails(10)

                if (emails.isEmpty()) {
                    setResult("No emails found.")
                    setLoading(false)
                    return@launch
                }

                // Run local agent logic
                val summaries = EmailAnalyzer.summarize(emails)
                val meetingRequired = MeetingDetector.isMeetingRequired(emails)
                val meetingEmails = MeetingDetector.detectMeetingEmails(emails)
                val proposedSlots = TimeExtractor.extractTimesFromEmails(meetingEmails)

                // Store for "Schedule Meeting" button
                extractedSlots.value = proposedSlots

                // Build result text identical to the old format
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
                        proposedSlots.forEach {
                            sb.appendLine("  • $it")
                        }
                    } else {
                        sb.appendLine("No specific times found in emails.")
                    }
                }

                setResult(sb.toString())
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
                setResult("Additional permissions required. Please try again.")
            } catch (e: Exception) {
                setResult("Error: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun scheduleMeeting(setMeetLink: (String) -> Unit) {
        val credential = authManager.getCredential()
        if (credential == null) {
            setMeetLink("Please sign in first.")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val calendarService = CalendarService(credential)

                // Use first extracted slot or default to 1 hour from now
                val startTimeMillis = resolveStartTime(extractedSlots.value.firstOrNull())

                val result = calendarService.createMeetingEvent(
                    title = "Meeting via AgenticAssistant",
                    startTimeMillis = startTimeMillis
                )

                setMeetLink(result.meetLink)
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
                setMeetLink("Additional permissions required. Please try again.")
            } catch (e: Exception) {
                setMeetLink("Failed to create meeting: ${e.message}")
            }
        }
    }

    /**
     * Tries to parse a slot string into epoch millis.
     * Falls back to 1 hour from now.
     */
    private fun resolveStartTime(slot: String?): Long {
        if (slot == null) {
            return System.currentTimeMillis() + 3600_000L
        }

        return try {
            val cal = Calendar.getInstance()

            val lower = slot.lowercase().trim()

            // Handle "tomorrow at X"
            if (lower.startsWith("tomorrow")) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val timePart = lower.removePrefix("tomorrow").removePrefix("at").trim()
                applyTimeToCal(cal, timePart)
                return cal.timeInMillis
            }

            // Handle day names like "Tuesday 10:30"
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

            // Try plain time like "5 pm" or "10:30 am"
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


@Composable
fun AgentScreen(
    modifier: Modifier = Modifier,
    isSignedIn: Boolean = false,
    onSignIn: () -> Unit = {},
    onScanInbox: ((String) -> Unit, (Boolean) -> Unit) -> Unit = { _, _ -> },
    onScheduleMeeting: ((String) -> Unit) -> Unit = {},
    extractedSlots: List<String> = emptyList()
) {

    var resultText by remember { mutableStateOf("") }
    var meetLink by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {

        Spacer(modifier = Modifier.height(24.dp))

        // Show sign-in button if not authenticated
        if (!isSignedIn) {
            Button(onClick = onSignIn) {
                Text("Sign in with Google")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                isLoading = true
                resultText = ""
                meetLink = ""
                onScanInbox(
                    { text -> resultText = text },
                    { loading -> isLoading = loading }
                )
            },
            enabled = !isLoading && isSignedIn
        ) {
            Text("Scan Inbox")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                onScheduleMeeting { link -> meetLink = link }
            },
            enabled = isSignedIn
        ) {
            Text("Schedule Meeting")
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        }

        if (resultText.isNotEmpty()) {
            Text(
                text = resultText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = Color.Black
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

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