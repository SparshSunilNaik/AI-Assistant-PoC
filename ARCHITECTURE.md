# Android Voice AI Assistant – System Architecture Documentation

**Project:** AgenticAssistant
**Package:** `com.example.agenticassistant`
**Platform:** Android (Kotlin, Jetpack Compose)
**Date:** March 7, 2026

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Project Architecture](#2-project-architecture)
3. [File-by-File Explanation](#3-file-by-file-explanation)
4. [Voice Assistant Pipeline](#4-voice-assistant-pipeline)
5. [Email Analysis Pipeline](#5-email-analysis-pipeline)
6. [Meeting Creation Pipeline](#6-meeting-creation-pipeline)
7. [Daily Briefing System](#7-daily-briefing-system)
8. [Background Email Monitor](#8-background-email-monitor)
9. [Permissions and Android Features](#9-permissions-and-android-features)
10. [Complete System Diagram](#10-complete-system-diagram)
11. [Data Flow Walkthrough](#11-data-flow-walkthrough)
12. [Future Extensions](#12-future-extensions)

---

## 1. SYSTEM OVERVIEW

AgenticAssistant is a **fully local, voice-driven Android AI assistant** that helps
users manage their email and calendar. The app runs entirely on the Android device
with no backend server. It authenticates directly with Google APIs using OAuth 2.0
and performs all intelligence logic (email analysis, meeting detection, time
extraction) in local Kotlin code.

### What the assistant does

The assistant can:

- **Read the user's Gmail inbox** and produce summaries of recent emails.
- **Detect meeting intent** by scanning email text for scheduling-related keywords.
- **Extract proposed meeting times** from email text using regex pattern matching.
- **Extract participant email addresses** from emails that suggest meetings.
- **Create Google Calendar events** with Google Meet video-conferencing links.
- **Add extracted participants as attendees** to calendar events.
- **Generate a daily briefing** that summarizes today's calendar events.
- **Accept voice commands** via Android's SpeechRecognizer and speak results aloud.
- **Monitor the inbox in the background** using WorkManager, sending notifications
  when meeting requests are detected.

### End-to-end workflow

```
User signs in with Google (OAuth 2.0)
        ↓
User taps "Scan Inbox" or says "check inbox"
        ↓
GmailService fetches the 10 most recent inbox messages
        ↓
EmailAnalyzer produces one-line summaries for each email
        ↓
MeetingDetector checks if any email contains meeting keywords
        ↓
TimeExtractor extracts time references ("5 pm", "tomorrow at 10")
        ↓
ParticipantExtractor extracts email addresses from meeting emails
        ↓
Results displayed on screen + spoken aloud by AssistantSpeaker
        ↓
User taps "Schedule Meeting" or says "schedule meeting"
        ↓
CalendarService creates a Google Calendar event with Google Meet link
        ↓
Extracted participants are added as event attendees
        ↓
Meeting link displayed on screen + spoken confirmation
```

### Key design principle

There is **no backend server**. Every operation — authentication, Gmail reading,
email analysis, calendar event creation, voice recognition — happens directly on
the Android device. The Google APIs are called from the device using the user's
own OAuth credentials.

---

## 2. PROJECT ARCHITECTURE

The application follows a **layered architecture** with six distinct layers.
Each layer has a single responsibility, and they communicate through well-defined
function calls.

### 2.1 Layer Diagram

```
┌─────────────────────────────────────────────────────┐
│                   UI LAYER                          │
│        Jetpack Compose (AgentScreen)                │
│   Buttons: Voice | Scan | Schedule | Briefing      │
└──────────────────────┬──────────────────────────────┘
                       │  user actions (button taps)
                       ▼
┌─────────────────────────────────────────────────────┐
│              CONTROLLER LAYER                       │
│              MainActivity.kt                        │
│   Orchestrates all actions, holds UI state,         │
│   routes voice commands to business logic           │
└──────┬──────────┬────────────┬──────────────────────┘
       │          │            │
       ▼          ▼            ▼
┌───────────┐ ┌──────────┐ ┌──────────────────────────┐
│ VOICE     │ │  AUTH     │ │  AGENT LOGIC LAYER       │
│ LAYER     │ │  LAYER   │ │  EmailAnalyzer            │
│           │ │          │ │  MeetingDetector           │
│ VoiceCmdMgr│ │GoogleAuth│ │  TimeExtractor            │
│ Assistant  │ │Manager  │ │  ParticipantExtractor      │
│ Speaker   │ │          │ │  DailyBriefingGenerator    │
└───────────┘ └────┬─────┘ └──────────────────────────┘
                   │                    │
                   ▼                    ▼
           ┌───────────────────────────────────────────┐
           │         GOOGLE API SERVICE LAYER          │
           │    GmailService      CalendarService      │
           │  (Gmail REST API)  (Calendar REST API)    │
           └───────────────────────────────────────────┘
                            │
                            ▼
           ┌───────────────────────────────────────────┐
           │        BACKGROUND WORKER LAYER            │
           │         InboxMonitorWorker                │
           │   (WorkManager periodic task)             │
           └───────────────────────────────────────────┘
```

### 2.2 Layer Descriptions

**UI Layer** — `AgentScreen` composable function.
Renders all buttons, text results, loading spinners, and the meeting link.
Entirely declarative. Contains zero business logic. Receives all state as
parameters and dispatches user actions via lambda callbacks.

**Controller Layer** — `MainActivity`.
The central orchestrator. Holds all mutable UI state (`resultText`, `meetLink`,
`isLoading`, etc.). Receives UI events, calls the appropriate agent logic and
service functions, updates state, and triggers voice responses.

**Authentication Layer** — `GoogleAuthManager`.
Manages Google Sign-In using `GoogleSignInClient`. Requests `gmail.readonly` and
`calendar.events` OAuth scopes. Builds `GoogleAccountCredential` objects that are
passed to GmailService and CalendarService.

**Agent Logic Layer** — The `agent/` package.
Five Kotlin `object` singletons that perform all email intelligence:
`EmailAnalyzer`, `MeetingDetector`, `TimeExtractor`, `ParticipantExtractor`,
`DailyBriefingGenerator`. These are pure functions with no Android dependencies
(except DailyBriefingGenerator which calls CalendarService).

**Google API Service Layer** — The `service/` package.
`GmailService` and `CalendarService` wrap the Google Java API client libraries.
Each is instantiated with a `GoogleAccountCredential` and performs network calls
on `Dispatchers.IO` using Kotlin coroutines.

**Voice Interface Layer** — The `voice/` package.
`VoiceCommandManager` wraps Android's `SpeechRecognizer` to convert speech to text
and classify it into one of four commands. `AssistantSpeaker` wraps Android's
`TextToSpeech` engine to speak results aloud.

**Background Worker Layer** — The `worker/` package.
`InboxMonitorWorker` is a `CoroutineWorker` scheduled via WorkManager to run every
15 minutes. It fetches recent emails, runs MeetingDetector, and shows a system
notification if a meeting request is found.

### 2.3 Inter-Layer Communication

| Source             | Target             | Mechanism                                |
|--------------------|--------------------|------------------------------------------|
| UI → Controller    | Button callbacks   | Compose lambda: `onScanInbox = { scanInbox() }` |
| Controller → Auth  | Credential request | `authManager.getCredential()`            |
| Controller → Service | API calls        | `GmailService(credential).fetchRecentEmails()` |
| Controller → Agent | Analysis           | `EmailAnalyzer.summarize(emails)`        |
| Controller → Voice | Voice I/O          | `voiceCommandManager.startListening(...)` / `assistantSpeaker.speak(...)` |
| Voice → Controller | Command routing    | Callback: `onResult = { rawText, command -> routeVoiceCommand(...) }` |
| Worker → Auth      | Credential request | `GoogleAuthManager(appContext).getCredential()` |
| Worker → Service   | Gmail fetch        | `GmailService(credential).fetchRecentEmails(5)` |
| Worker → Agent     | Meeting detection  | `MeetingDetector.isMeetingRequired(emails)` |

---

## 3. FILE-BY-FILE EXPLANATION

### 3.1 model/EmailMessage.kt

**Purpose:** Data model representing a single email message.

**Class:** `EmailMessage` (data class)

**Fields:**
| Field     | Type   | Description                               |
|-----------|--------|-------------------------------------------|
| `id`      | String | Gmail message ID                          |
| `subject` | String | Email subject line                        |
| `snippet` | String | First ~100 characters of email body       |
| `from`    | String | Sender (e.g., "John Doe <john@example.com>") |
| `date`    | String | Date header string                        |

**Used by:** GmailService (produces it), EmailAnalyzer, MeetingDetector,
TimeExtractor, ParticipantExtractor (consume it).

---

### 3.2 auth/GoogleAuthManager.kt

**Purpose:** Manages the complete Google Sign-In lifecycle and OAuth credential
creation. This is the authentication gateway for the entire application.

**Class:** `GoogleAuthManager`

**Key Functions:**

| Function               | Description                                                     |
|------------------------|-----------------------------------------------------------------|
| `getSignInIntent()`    | Returns an `Intent` to launch the Google Sign-In chooser UI.    |
| `handleSignInResult()` | Parses the sign-in result `Intent` into a `GoogleSignInAccount`. Logs the status code and human-readable message on failure. |
| `getSignInError()`     | Returns a user-facing error string from a failed sign-in result. |
| `isSignedIn()`         | Returns `true` if the user has an active session with the required Gmail and Calendar scopes. |
| `getCredential()`      | Builds a `GoogleAccountCredential` from the current signed-in account. This credential is passed to `GmailService` and `CalendarService`. |
| `signOut()`            | Signs the user out and clears cached state.                     |
| `revokeAccess()`       | Fully revokes OAuth access for a clean restart.                 |

**OAuth Scopes Requested:**
- `https://www.googleapis.com/auth/gmail.readonly` — Read-only access to Gmail.
- `https://www.googleapis.com/auth/calendar.events` — Read/write access to calendar events.

**Error Handling:** Maps numeric Google status codes (10 = DEVELOPER_ERROR,
7 = NETWORK_ERROR, 12500 = SIGN_IN_FAILED, etc.) to detailed human-readable
messages via `getStatusCodeMessage()`.

**Used by:** `MainActivity` (for sign-in flow and credential building),
`InboxMonitorWorker` (for background credential access).

---

### 3.3 service/GmailService.kt

**Purpose:** Fetches recent emails from the authenticated user's Gmail inbox
via the Gmail REST API.

**Class:** `GmailService`

**Constructor:** Takes a `GoogleAccountCredential`. Builds a `Gmail` client
using `NetHttpTransport`, `GsonFactory`, and the credential.

**Key Functions:**

| Function              | Description                                                        |
|-----------------------|--------------------------------------------------------------------|
| `fetchRecentEmails()` | Suspend function. Lists up to `maxResults` (default 10) messages from the `INBOX` label. For each message, fetches metadata headers (Subject, From, Date) and the snippet. Returns `List<EmailMessage>`. |

**Implementation Details:**
- Runs on `Dispatchers.IO` via `withContext`.
- First calls `gmail.users().messages().list("me")` to get message IDs.
- Then calls `gmail.users().messages().get("me", id)` with `format=metadata` for
  each message to retrieve headers.
- Individual message fetch failures are silently skipped (`mapNotNull` + try/catch
  returning `null`).

**Used by:** `MainActivity.scanInbox()`, `InboxMonitorWorker.doWork()`.

---

### 3.4 service/CalendarService.kt

**Purpose:** Creates Google Calendar events with Google Meet links, fetches
today's events, and checks calendar availability for free time slots.

**Class:** `CalendarService`

**Constructor:** Takes a `GoogleAccountCredential`. Builds a `Calendar` client.

**Data Class:** `MeetingResult` — contains `meetLink`, `eventId`, `scheduledTime`.

**Key Functions:**

| Function                           | Description                                        |
|------------------------------------|----------------------------------------------------|
| `createMeetingEvent()`             | Creates a 30-minute event on the primary calendar with a Google Meet link. Uses `ConferenceData` with `hangoutsMeet` solution key and `conferenceDataVersion=1`. |
| `createMeetingEventWithAttendees()`| Same as above but also attaches a list of `EventAttendee` objects and calls `.setSendUpdates("all")` so attendees receive email invitations. |
| `getTodayEvents()`                 | Queries the primary calendar for all events between midnight and 11:59 PM today. Returns a list of human-readable strings like `"Project sync at 10:00 AM"`. |
| `getFreeSlots()`                   | Given a requested time, fetches events in a 24-hour window, identifies busy intervals, and returns up to 5 free 30-minute slots formatted as `"5:30 PM"` or `"Tomorrow 10:00 AM"`. |

**Google Meet Link Generation:**
The Meet link is generated by attaching a `ConferenceData` object with a
`CreateConferenceRequest` that has a random `requestId` (UUID) and a
`ConferenceSolutionKey` of type `"hangoutsMeet"`. The Calendar API then
generates the Meet link automatically. The link is extracted from
`createdEvent.conferenceData.entryPoints` where `entryPointType == "video"`.

**Used by:** `MainActivity.scheduleMeeting()`, `MainActivity.dailyBriefing()`
(via `DailyBriefingGenerator`).

---

### 3.5 agent/EmailAnalyzer.kt

**Purpose:** Produces one-line summaries for a list of emails.

**Object:** `EmailAnalyzer` (singleton)

**Key Functions:**

| Function       | Description                                                     |
|----------------|-----------------------------------------------------------------|
| `summarize()`  | Takes `List<EmailMessage>`, returns `List<String>`. For each email, produces `"Subject — Snippet"`. The snippet is truncated to 120 characters with an ellipsis appended if longer. Blank subjects are replaced with `"(no subject)"`. |

**Used by:** `MainActivity.scanInbox()`.

---

### 3.6 agent/MeetingDetector.kt

**Purpose:** Determines whether any emails in a list contain meeting-related
language, and filters the list to only those that do.

**Object:** `MeetingDetector` (singleton)

**Keywords searched:** `"meeting"`, `"discuss"`, `"call"`, `"sync"`, `"schedule"`,
`"agenda"`, `"catch up"`, `"standup"`, `"stand-up"`, `"huddle"`, `"conference"`.

**Key Functions:**

| Function                | Description                                                  |
|-------------------------|--------------------------------------------------------------|
| `isMeetingRequired()`   | Returns `true` if **any** email's subject+snippet contains at least one meeting keyword (case-insensitive). |
| `detectMeetingEmails()` | Returns a filtered `List<EmailMessage>` containing **only** those emails that match at least one meeting keyword. |

**Used by:** `MainActivity.scanInbox()`, `InboxMonitorWorker.doWork()`.

---

### 3.7 agent/TimeExtractor.kt

**Purpose:** Extracts human-readable time references from email text using
regular expressions.

**Object:** `TimeExtractor` (singleton)

**Regex Patterns:**

| Pattern            | Matches                                                     |
|--------------------|-------------------------------------------------------------|
| `TIME_PATTERN`     | Standalone times: `"5 pm"`, `"10 am"`, `"5:30 pm"`, `"2:00 PM"` |
| `TOMORROW_PATTERN` | `"tomorrow at 4"`, `"tomorrow at 10:30 am"`                |
| `DAY_TIME_PATTERN` | `"Monday at 3 pm"`, `"Tuesday 10:30 am"`                   |

**Key Functions:**

| Function                  | Description                                          |
|---------------------------|------------------------------------------------------|
| `extractTimes()`          | Takes a single text string, returns `List<String>` of time slot strings like `"Tomorrow at 4"` or `"Tuesday 10:30 am"`. Uses a `Set` internally to avoid duplicates. |
| `extractTimesFromEmails()`| Calls `extractTimes()` on `subject + snippet` of each email in a list. Returns combined unique results. |

**Deduplication logic:** Standalone times (e.g., `"3 pm"`) are not added if
they already appear as the suffix of a day-based match (e.g., `"Monday 3 pm"`).

**Used by:** `MainActivity.scanInbox()`.

---

### 3.8 agent/ParticipantExtractor.kt

**Purpose:** Extracts email addresses from email content using regex.

**Object:** `ParticipantExtractor` (singleton)

**Regex:** `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`
Matches standard email address formats.

**Key Functions:**

| Function              | Description                                              |
|-----------------------|----------------------------------------------------------|
| `extractFromEmail()`  | Scans a single email's `from`, `subject`, and `snippet` fields. Returns `List<String>` of unique lowercase email addresses found. |
| `extractFromEmails()` | Applies `extractFromEmail()` to all emails in a list. Returns globally unique addresses. |

**Used by:** `MainActivity.scanInbox()`. The extracted participants are stored
in `extractedParticipants` state and later used by `scheduleMeeting()` to add
attendees to the calendar event.

---

### 3.9 agent/DailyBriefingGenerator.kt

**Purpose:** Generates a spoken/readable daily briefing from today's calendar.

**Object:** `DailyBriefingGenerator` (singleton)

**Key Functions:**

| Function     | Description                                                       |
|--------------|-------------------------------------------------------------------|
| `generate()` | Suspend function. Takes an authenticated `CalendarService`, calls `getTodayEvents()`, and produces a human-readable string. Example output: `"Today you have 3 meetings. 1. Project sync at 10:00 AM. 2. Research discussion at 2:00 PM. 3. Team standup at 4:30 PM."` If no events, returns `"You have no meetings scheduled for today. Your calendar is clear."` |

**Used by:** `MainActivity.dailyBriefing()`.

---

### 3.10 voice/VoiceCommandManager.kt

**Purpose:** Manages voice input via Android's SpeechRecognizer, converts speech
to text, and classifies the recognized text into a command.

**Class:** `VoiceCommandManager`

**Enum:** `Command` — `SCAN_INBOX`, `SCHEDULE_MEETING`, `DAILY_BRIEFING`, `UNKNOWN`

**Key Functions:**

| Function           | Description                                                  |
|--------------------|--------------------------------------------------------------|
| `startListening()` | Creates a `SpeechRecognizer`, sets a `RecognitionListener`, builds a `RecognizerIntent` with `LANGUAGE_MODEL_FREE_FORM`, and starts listening. On success, calls `parseCommand()` and invokes the `onResult` callback with `(rawText, command)`. On error, calls `onError` with a human-readable message. |
| `parseCommand()`   | Lowercase keyword matching. Maps recognized text to a `Command`. |
| `stopListening()`  | Stops the active recognizer.                                  |
| `destroy()`        | Destroys the recognizer and releases resources.               |

**Recognized Phrases → Commands:**

| Phrase(s)                                                        | Command             |
|------------------------------------------------------------------|---------------------|
| "scan inbox", "check inbox", "check my inbox", "check email", etc. | `SCAN_INBOX`      |
| "schedule meeting", "create meeting", "book meeting", etc.       | `SCHEDULE_MEETING`  |
| "daily briefing", "what meetings", "meetings today", "what's on my calendar", etc. | `DAILY_BRIEFING` |
| Anything else                                                    | `UNKNOWN`           |

**Used by:** `MainActivity.startVoiceListening()`.

---

### 3.11 voice/AssistantSpeaker.kt

**Purpose:** Text-to-Speech wrapper. Speaks assistant responses aloud.

**Class:** `AssistantSpeaker`

**Key Functions:**

| Function     | Description                                                      |
|--------------|------------------------------------------------------------------|
| `speak()`    | Speaks the given message using `TextToSpeech.QUEUE_FLUSH` (interrupts any current speech). Uses `Locale.US` as the language. |
| `stop()`     | Stops any current speech.                                        |
| `shutdown()` | Releases all TTS resources. Called from `Activity.onDestroy()`.  |

**Initialization:** The `TextToSpeech` engine is initialized in the `init` block.
An `isInitialized` flag prevents calls before the engine is ready. If
initialization fails, messages are silently dropped (logged to Logcat).

**Used by:** `MainActivity` — called after every action to provide voice feedback.

---

### 3.12 worker/InboxMonitorWorker.kt

**Purpose:** Background worker that periodically checks Gmail for meeting requests
and shows a system notification if one is detected.

**Class:** `InboxMonitorWorker` extends `CoroutineWorker`

**Key Functions:**

| Function            | Description                                                |
|---------------------|------------------------------------------------------------|
| `doWork()`          | Gets credentials via `GoogleAuthManager`, fetches 5 recent emails via `GmailService`, runs `MeetingDetector.isMeetingRequired()`. If true, shows a notification. Returns `Result.success()` normally or `Result.retry()` on error. |
| `showNotification()`| Creates a `NotificationChannel` ("Inbox Monitor") and posts a notification with the meeting count. |

**Scheduling:** Registered in `MainActivity.scheduleInboxMonitor()` as a
`PeriodicWorkRequest` with a 15-minute interval using `ExistingPeriodicWorkPolicy.KEEP`
(does not replace an existing schedule).

**Used by:** Android WorkManager (system-scheduled).

---

### 3.13 MainActivity.kt

**Purpose:** The central orchestrator of the entire application. Hosts the
Compose UI, manages all mutable state, handles authentication, routes voice
commands, and coordinates all agent logic and service calls.

**Class:** `MainActivity` extends `ComponentActivity`

**Key State Variables:**

| State                  | Type             | Purpose                              |
|------------------------|------------------|--------------------------------------|
| `isSignedIn`           | `Boolean`        | Controls UI enabled/disabled states  |
| `extractedSlots`       | `List<String>`   | Time slots from last inbox scan      |
| `extractedParticipants`| `List<String>`   | Email addresses from last scan       |
| `resultText`           | `String`         | Main text display area               |
| `meetLink`             | `String`         | Google Meet link after scheduling    |
| `isLoading`            | `Boolean`        | Shows/hides loading spinner          |
| `isListening`          | `Boolean`        | Indicates active voice recording     |
| `voiceStatus`          | `String`         | Voice feedback text below mic button |

**Activity Result Launchers:**

| Launcher                  | Purpose                                          |
|---------------------------|--------------------------------------------------|
| `signInLauncher`          | Handles the Google Sign-In result                |
| `consentLauncher`         | Handles additional OAuth consent prompts         |
| `audioPermissionLauncher` | Requests `RECORD_AUDIO` runtime permission       |

**Key Functions:**

| Function               | Description                                             |
|------------------------|---------------------------------------------------------|
| `onCreate()`           | Initializes auth, voice, and TTS managers. Checks existing sign-in. Schedules background worker. Sets up Compose UI. |
| `onDestroy()`          | Destroys voice recognizer and shuts down TTS engine.    |
| `requestVoiceInput()`  | Checks RECORD_AUDIO permission, requests it if needed, then starts listening. |
| `startVoiceListening()`| Activates `VoiceCommandManager`, updates `isListening` state. |
| `routeVoiceCommand()`  | `when` block that maps `Command` enum values to `scanInbox()`, `scheduleMeeting()`, or `dailyBriefing()`. |
| `scanInbox()`          | Full email analysis pipeline. See Section 5.            |
| `scheduleMeeting()`    | Meeting creation pipeline. See Section 6.               |
| `dailyBriefing()`      | Daily briefing pipeline. See Section 7.                 |
| `scheduleInboxMonitor()`| Registers the WorkManager periodic task.               |
| `resolveStartTime()`   | Parses a time slot string (e.g., "Tomorrow at 10:30 am") into epoch milliseconds. Handles day names, "tomorrow", and plain times. Falls back to 1 hour from now. |
| `applyTimeToCal()`     | Helper that parses "5 pm" / "10:30 am" style strings and applies them to a `Calendar` instance. |

**Composable:** `AgentScreen`

A stateless composable that renders the entire UI. It receives all state as
parameters and dispatches all actions via lambdas. Layout:

```
┌──────────────────────────────────┐
│  [Sign in with Google]           │  ← Shown only when !isSignedIn
│  [🎤 Voice Command]             │  ← Full width
│   "Heard: check inbox"          │  ← voiceStatus
│  [📥 Scan Inbox] [📅 Schedule]  │  ← Side by side
│  [📊 Daily Briefing]            │  ← Full width
│  (CircularProgressIndicator)    │  ← Shown when isLoading
│  Result text area               │  ← resultText
│  "Meeting Created!"             │  ← Shown when meetLink is set
│  https://meet.google.com/...    │  ← meetLink in blue
└──────────────────────────────────┘
```

---

## 4. VOICE ASSISTANT PIPELINE

The voice pipeline converts a spoken phrase into an executed action in seven
discrete steps.

### Step 1 — User taps 🎤 Voice Command button

The `onVoiceCommand` lambda in `AgentScreen` calls `requestVoiceInput()` in
`MainActivity`.

### Step 2 — Permission check

`requestVoiceInput()` checks if `RECORD_AUDIO` permission is granted via
`ContextCompat.checkSelfPermission()`. If not, it launches the
`audioPermissionLauncher` which shows the system permission dialog. If granted,
it calls `startVoiceListening()`.

### Step 3 — SpeechRecognizer activation

`startVoiceListening()` sets `isListening = true` and `voiceStatus = "🎤 Listening..."`.
It then calls `voiceCommandManager.startListening()` with two callbacks:
`onResult` and `onError`.

Inside `VoiceCommandManager.startListening()`:
1. Checks if speech recognition is available on the device.
2. Creates a `SpeechRecognizer` instance.
3. Attaches a `RecognitionListener` with lifecycle callbacks.
4. Builds a `RecognizerIntent` with `LANGUAGE_MODEL_FREE_FORM` and max 1 result.
5. Calls `speechRecognizer.startListening(intent)`.

### Step 4 — Speech-to-text conversion

Android's SpeechRecognizer (backed by Google's speech recognition service)
captures audio from the microphone, processes it, and returns the recognized
text string via `onResults()`. The listener extracts the first result from
`SpeechRecognizer.RESULTS_RECOGNITION`.

### Step 5 — Command classification

The recognized text is passed to `parseCommand()`, which performs
**case-insensitive substring matching** against a set of known phrases.
Returns one of: `SCAN_INBOX`, `SCHEDULE_MEETING`, `DAILY_BRIEFING`, or `UNKNOWN`.

### Step 6 — Command routing

Back in `MainActivity`, the `onResult` callback receives `(rawText, command)`.
It updates `voiceStatus` to show what was heard, then calls
`routeVoiceCommand(command, rawText)`, which is a `when` expression:

- `SCAN_INBOX` → speaks "Scanning your inbox now." → calls `scanInbox()`
- `SCHEDULE_MEETING` → speaks "Scheduling a meeting." → calls `scheduleMeeting()`
- `DAILY_BRIEFING` → speaks "Getting your daily briefing." → calls `dailyBriefing()`
- `UNKNOWN` → speaks an error message suggesting valid commands

### Step 7 — Spoken response

After the action completes, `assistantSpeaker.speak()` is called with a summary:
- Scan: `"Inbox scanned. 10 emails found. 3 emails suggest meetings."`
- Schedule: `"Meeting scheduled successfully with 2 participants."`
- Briefing: `"Today you have 3 meetings. 1. Project sync at 10:00 AM..."`

```
Complete pipeline:

  Microphone (hardware)
       ↓
  Android SpeechRecognizer (OS service)
       ↓
  VoiceCommandManager.onResults() — receives raw text
       ↓
  VoiceCommandManager.parseCommand() — keyword matching
       ↓
  MainActivity.routeVoiceCommand() — dispatches to handler
       ↓
  scanInbox() / scheduleMeeting() / dailyBriefing()
       ↓
  AssistantSpeaker.speak() — TextToSpeech output
       ↓
  Device speaker (hardware)
```

---

## 5. EMAIL ANALYSIS PIPELINE

When the user triggers "Scan Inbox" (button or voice), the following pipeline
executes inside `MainActivity.scanInbox()`:

### Step 1 — Authentication

```kotlin
val credential = authManager.getCredential()
```

`GoogleAuthManager.getCredential()` retrieves the signed-in Google account and
builds a `GoogleAccountCredential` with `gmail.readonly` and `calendar.events`
scopes. If the user is not signed in, the pipeline aborts with a message.

### Step 2 — Email Fetch

```kotlin
val gmailService = GmailService(credential)
val emails = gmailService.fetchRecentEmails(10)
```

`GmailService` calls the Gmail REST API to list up to 10 inbox messages, then
fetches metadata (Subject, From, Date headers) and snippet for each. Returns
`List<EmailMessage>`.

### Step 3 — Summarization

```kotlin
val summaries = EmailAnalyzer.summarize(emails)
```

Produces a one-line summary for each email: `"Subject — Snippet (truncated to 120 chars)"`.
Returns `List<String>` with one entry per email.

### Step 4 — Meeting Detection

```kotlin
val meetingRequired = MeetingDetector.isMeetingRequired(emails)
val meetingEmails = MeetingDetector.detectMeetingEmails(emails)
```

`isMeetingRequired()` returns `true` if **any** email matches the keyword list.
`detectMeetingEmails()` returns the **subset** of emails that match.

Keyword matching is done by concatenating `subject + snippet`, converting to
lowercase, and checking if it contains any keyword from the list:
`meeting`, `discuss`, `call`, `sync`, `schedule`, `agenda`, `catch up`,
`standup`, `stand-up`, `huddle`, `conference`.

### Step 5 — Time Extraction

```kotlin
val proposedSlots = TimeExtractor.extractTimesFromEmails(meetingEmails)
```

Applied only to the meeting-related emails. For each email, concatenates
`subject + snippet` and runs three regex patterns to extract:
- Standalone times: `"5 pm"`, `"10:30 am"`
- Tomorrow references: `"Tomorrow at 4"`
- Day references: `"Tuesday 10:30 am"`

Returns a deduplicated list of time slot strings.

### Step 6 — Participant Extraction

```kotlin
val participants = ParticipantExtractor.extractFromEmails(meetingEmails)
```

Applied only to the meeting-related emails. For each email, scans `from`,
`subject`, and `snippet` fields with an email-address regex. Returns a
deduplicated list of lowercase email addresses.

### Step 7 — State Update and Display

The results are stored in state variables:
```kotlin
extractedSlots.value = proposedSlots
extractedParticipants.value = participants
```

A formatted string is built with sections for summaries, proposed slots, and
detected participants, then assigned to `resultText.value`. The Compose UI
re-renders automatically.

### Step 8 — Voice Summary

```kotlin
assistantSpeaker.speak("Inbox scanned. 10 emails found. 3 emails suggest meetings.")
```

```
Complete pipeline:

  GoogleAuthManager.getCredential()
       ↓
  GmailService.fetchRecentEmails(10)     → List<EmailMessage>
       ↓
  EmailAnalyzer.summarize(emails)        → List<String> summaries
       ↓
  MeetingDetector.isMeetingRequired()    → Boolean
  MeetingDetector.detectMeetingEmails()  → List<EmailMessage> meetingEmails
       ↓
  TimeExtractor.extractTimesFromEmails() → List<String> proposedSlots
       ↓
  ParticipantExtractor.extractFromEmails() → List<String> participants
       ↓
  UI state update → Compose recomposition
       ↓
  AssistantSpeaker.speak() → audio output
```

---

## 6. MEETING CREATION PIPELINE

When the user triggers "Schedule Meeting" (button or voice), the following
pipeline executes inside `MainActivity.scheduleMeeting()`:

### Step 1 — Authentication

Same as the email pipeline. Obtains a `GoogleAccountCredential`.

### Step 2 — Time Resolution

```kotlin
val startTimeMillis = resolveStartTime(extractedSlots.value.firstOrNull())
```

`resolveStartTime()` takes the **first** extracted time slot from the last
inbox scan and converts it to epoch milliseconds. The parser handles:

- `null` → defaults to 1 hour from now
- `"Tomorrow at 10:30 am"` → tomorrow's date with 10:30 AM
- `"Tuesday 3 pm"` → next Tuesday at 3:00 PM
- `"5 pm"` → today at 5:00 PM (or tomorrow if already past)

The helper `applyTimeToCal()` parses the time portion, handling AM/PM conversion.

### Step 3 — Attendee Decision

```kotlin
val attendees = extractedParticipants.value
```

If the last inbox scan found participant email addresses, they are used.

### Step 4 — Calendar Event Creation

**Without attendees:**
```kotlin
calendarService.createMeetingEvent(title, startTimeMillis)
```

**With attendees:**
```kotlin
calendarService.createMeetingEventWithAttendees(title, startTimeMillis, attendeeEmails = attendees)
```

Both methods:
1. Build an `Event` object with `summary`, `start`, `end` (default 30 min duration).
2. Attach `ConferenceData` with a `CreateConferenceRequest` specifying
   `hangoutsMeet` as the conference solution.
3. Call `calendar.events().insert("primary", event).setConferenceDataVersion(1).execute()`.
4. The `WithAttendees` variant also sets `attendees` on the event and calls
   `.setSendUpdates("all")` so Google sends email invitations.
5. Extract the Google Meet link from `conferenceData.entryPoints` where
   `entryPointType == "video"`.

### Step 5 — Result Display

```kotlin
meetLink.value = result.meetLink
assistantSpeaker.speak("Meeting scheduled successfully with 2 participants.")
```

The UI shows "Meeting Created!" with the Meet link in blue.

```
Complete pipeline:

  extractedSlots.value.firstOrNull()
       ↓
  resolveStartTime() → epoch millis
       ↓
  extractedParticipants.value → attendee list
       ↓
  CalendarService.createMeetingEvent[WithAttendees]()
       ↓
  Google Calendar API → Event created
       ↓
  ConferenceData → Google Meet link generated
       ↓
  MeetingResult(meetLink, eventId, scheduledTime)
       ↓
  UI displays meetLink + AssistantSpeaker confirms
```

---

## 7. DAILY BRIEFING SYSTEM

When the user triggers "Daily Briefing" (button or voice), the following
pipeline executes inside `MainActivity.dailyBriefing()`:

### Step 1 — Authentication

Obtains `GoogleAccountCredential` as usual.

### Step 2 — Fetch Today's Events

```kotlin
val calendarService = CalendarService(credential)
val briefing = DailyBriefingGenerator.generate(calendarService)
```

Inside `DailyBriefingGenerator.generate()`:

```kotlin
val events = calendarService.getTodayEvents()
```

`getTodayEvents()` queries the Google Calendar API for all events on the user's
primary calendar between midnight (00:00:00) and end-of-day (23:59:59) today.
It uses `orderBy=startTime` and `singleEvents=true` to expand recurring events.
Each event is formatted as `"Event Title at HH:MM AM/PM"` or `"Event Title at All day"`.

### Step 3 — Generate Briefing Text

If events are empty:
```
"You have no meetings scheduled for today. Your calendar is clear."
```

If events exist:
```
"Today you have 3 meetings. 1. Project sync at 10:00 AM. 2. Research discussion
at 2:00 PM. 3. Team standup at 4:30 PM."
```

### Step 4 — Display and Speak

```kotlin
resultText.value = "📊 Daily Briefing:\n\n$briefing"
assistantSpeaker.speak(briefing)
```

The full briefing is shown on screen and read aloud by the TTS engine.

```
Complete pipeline:

  CalendarService.getTodayEvents()
       ↓
  Google Calendar API → events between 00:00 and 23:59 today
       ↓
  Format each event as "Title at Time"
       ↓
  DailyBriefingGenerator.generate() → human-readable summary string
       ↓
  UI displays briefing + AssistantSpeaker reads it aloud
```

---

## 8. BACKGROUND EMAIL MONITOR

### Architecture

The background monitoring system uses **Android WorkManager** with a
`CoroutineWorker` to periodically check the user's inbox for meeting requests
without the app being open.

### Scheduling

In `MainActivity.onCreate()`:

```kotlin
val workRequest = PeriodicWorkRequestBuilder<InboxMonitorWorker>(
    15, TimeUnit.MINUTES
).build()

WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    InboxMonitorWorker.WORK_NAME,      // "inbox_monitor_work"
    ExistingPeriodicWorkPolicy.KEEP,    // Don't replace existing
    workRequest
)
```

- **Interval:** Every 15 minutes (WorkManager's minimum periodic interval).
- **Policy:** `KEEP` — if the work is already scheduled, do not replace it.
- **Lifecycle:** Survives app closure. WorkManager ensures the task runs even
  after the app process is killed (subject to system battery optimization).

### Execution Flow (doWork)

```
1. Create GoogleAuthManager with application context
2. Get credential → if null (user not signed in), return Result.success()
3. Create GmailService, fetch 5 most recent emails
4. If no emails found, return Result.success()
5. Run MeetingDetector.isMeetingRequired(emails)
6. If true:
   a. Count how many meeting emails were found
   b. Show notification: "Meeting request detected — N emails suggest a meeting."
7. Return Result.success()
8. On exception: return Result.retry() (WorkManager will reschedule)
```

### Notification System

- **Channel ID:** `"inbox_monitor_channel"`
- **Channel Name:** `"Inbox Monitor"`
- **Importance:** `IMPORTANCE_DEFAULT` (sound + notification shade)
- **Notification ID:** `1001` (fixed — new notifications replace the previous one)
- **Auto-cancel:** `true` (dismissed when tapped)
- **Icon:** `R.drawable.ic_launcher_foreground`

The notification channel is created inside `showNotification()` every time. This
is safe because `createNotificationChannel()` is a no-op if the channel already
exists.

---

## 9. PERMISSIONS AND ANDROID FEATURES

### 9.1 Permissions

| Permission          | Declared In         | Purpose                                                  |
|---------------------|---------------------|----------------------------------------------------------|
| `INTERNET`          | AndroidManifest.xml | Required for all Google API calls (Gmail, Calendar, Speech Recognition). Granted automatically at install time. |
| `RECORD_AUDIO`      | AndroidManifest.xml | Required for `SpeechRecognizer` to capture microphone input. **Runtime permission** — requested when user taps Voice Command button. |
| `POST_NOTIFICATIONS`| AndroidManifest.xml | Required on Android 13+ (API 33) to show notifications from `InboxMonitorWorker`. On API 26-32, notifications work without this permission. |

### 9.2 Android Features Used

**SpeechRecognizer** (`android.speech.SpeechRecognizer`)
- System service that converts speech to text.
- Used in `VoiceCommandManager`.
- Requires `RECORD_AUDIO` permission.
- Uses `LANGUAGE_MODEL_FREE_FORM` for natural language input.
- Operates asynchronously via `RecognitionListener` callbacks.

**TextToSpeech** (`android.speech.tts.TextToSpeech`)
- System service that converts text to spoken audio.
- Used in `AssistantSpeaker`.
- Initialized asynchronously (readiness callback in constructor).
- Configured with `Locale.US`.
- Uses `QUEUE_FLUSH` mode (interrupts current speech).

**WorkManager** (`androidx.work.WorkManager`)
- Jetpack library for deferrable, guaranteed background work.
- Used to schedule `InboxMonitorWorker` as a periodic task (every 15 min).
- Survives process death and device reboot.
- `CoroutineWorker` variant supports suspend functions.

**Google Sign-In** (`com.google.android.gms.auth.api.signin`)
- Play Services SDK for OAuth 2.0 authentication.
- Used in `GoogleAuthManager`.
- Requests `gmail.readonly` and `calendar.events` scopes.
- Produces `GoogleAccountCredential` for Google API client libraries.
- Sign-in flow uses `ActivityResultContracts.StartActivityForResult`.

**Jetpack Compose** (`androidx.compose`)
- Declarative UI framework for the entire user interface.
- `AgentScreen` composable receives state as parameters.
- `Scaffold` provides Material 3 structure.
- `mutableStateOf` at the Activity level triggers recomposition.

---

## 10. COMPLETE SYSTEM DIAGRAM

```
╔═══════════════════════════════════════════════════════════════════════╗
║                           USER                                      ║
║              (Voice via Microphone / Touch via Screen)               ║
╚══════════════════════════════╦════════════════════════════════════════╝
                               ║
          ┌────────────────────╨────────────────────┐
          │              UI LAYER                    │
          │          AgentScreen (Compose)           │
          │                                          │
          │  ┌────────┐ ┌──────┐ ┌────────┐ ┌─────┐│
          │  │🎤 Voice│ │📥Scan│ │📅Sched.│ │📊Brf││
          │  └───┬────┘ └──┬───┘ └───┬────┘ └──┬──┘│
          │      │         │         │          │   │
          └──────┼─────────┼─────────┼──────────┼───┘
                 │         │         │          │
                 ▼         ▼         ▼          ▼
╔═══════════════════════════════════════════════════════════════════════╗
║                      MAINACTIVITY (Controller)                      ║
║                                                                     ║
║  requestVoiceInput()  scanInbox()  scheduleMeeting()  dailyBriefing()║
║        │                  │              │                │         ║
╚════════╪══════════════════╪══════════════╪════════════════╪═════════╝
         │                  │              │                │
    ┌────▼────┐        ┌────▼────┐    ┌────▼─────┐    ┌────▼─────┐
    │  VOICE  │        │  AUTH   │    │  AUTH    │    │  AUTH    │
    │  LAYER  │        │  LAYER  │    │  LAYER   │    │  LAYER   │
    │         │        │         │    │          │    │          │
    │VoiceCmd │        │GoogleAuth│    │GoogleAuth│    │GoogleAuth│
    │Manager  │        │Manager  │    │Manager   │    │Manager   │
    │         │        │         │    │          │    │          │
    │Assistant│        └────┬────┘    └─────┬────┘    └────┬─────┘
    │Speaker  │             │               │              │
    └─────────┘             ▼               ▼              ▼
                     ┌─────────────────────────────────────────────┐
                     │           GOOGLE API SERVICE LAYER          │
                     │                                             │
                     │  GmailService          CalendarService      │
                     │  .fetchRecentEmails()  .createMeetingEvent()│
                     │                        .getTodayEvents()    │
                     │                        .getFreeSlots()      │
                     └──────────┬──────────────────┬───────────────┘
                                │                  │
                     ┌──────────▼──────────┐       │
                     │   AGENT LOGIC LAYER │       │
                     │                     │       │
                     │  EmailAnalyzer      │       │
                     │  MeetingDetector    │       │
                     │  TimeExtractor      │◄──────┘
                     │  ParticipantExtractor│   (DailyBriefingGenerator
                     │  DailyBriefingGen.  │    calls CalendarService)
                     └─────────────────────┘
                                │
                     ┌──────────▼──────────────────────────────────┐
                     │         BACKGROUND WORKER LAYER             │
                     │         InboxMonitorWorker                  │
                     │                                             │
                     │  Runs every 15 minutes via WorkManager      │
                     │  Uses: GoogleAuthManager → GmailService     │
                     │        → MeetingDetector → Notification     │
                     └─────────────────────────────────────────────┘
                                │
                     ┌──────────▼──────────────────────────────────┐
                     │          EXTERNAL SERVICES                  │
                     │                                             │
                     │  Google Gmail REST API                      │
                     │  Google Calendar REST API                   │
                     │  Google Meet (via Calendar conferenceData)  │
                     │  Google Speech Recognition (SpeechRecognizer)│
                     │  Android TTS Engine                         │
                     └─────────────────────────────────────────────┘
```

---

## 11. DATA FLOW WALKTHROUGH

### Scenario: User says "check inbox"

The following is the exact sequence of code execution, function by function.

**Phase 1 — Voice Input**

```
1.  User taps 🎤 Voice Command button
2.  AgentScreen → onVoiceCommand lambda fires
3.  MainActivity.requestVoiceInput() called
4.  ContextCompat.checkSelfPermission(RECORD_AUDIO) → PERMISSION_GRANTED
5.  MainActivity.startVoiceListening() called
6.  isListening.value = true
7.  voiceStatus.value = "🎤 Listening..."
8.  voiceCommandManager.startListening(onResult, onError) called
9.  SpeechRecognizer.isRecognitionAvailable() → true
10. SpeechRecognizer.createSpeechRecognizer() → new instance
11. RecognitionListener attached
12. RecognizerIntent built (LANGUAGE_MODEL_FREE_FORM, maxResults=1)
13. speechRecognizer.startListening(intent)
14. — User speaks "check inbox" —
15. RecognitionListener.onBeginningOfSpeech()
16. RecognitionListener.onEndOfSpeech()
17. RecognitionListener.onResults(bundle)
18. Extracted text: "check inbox"
19. VoiceCommandManager.parseCommand("check inbox")
20.   "check inbox".lowercase() contains "check inbox" → true
21.   Returns Command.SCAN_INBOX
22. onResult callback invoked with ("check inbox", SCAN_INBOX)
```

**Phase 2 — Command Routing**

```
23. MainActivity: isListening.value = false
24. voiceStatus.value = "Heard: \"check inbox\""
25. routeVoiceCommand(SCAN_INBOX, "check inbox") called
26. Case SCAN_INBOX matched
27. assistantSpeaker.speak("Scanning your inbox now.")
28. TextToSpeech.speak() → audio output begins
29. scanInbox() called
```

**Phase 3 — Email Fetch**

```
30. authManager.getCredential() → GoogleAccountCredential (non-null)
31. isLoading.value = true, resultText.value = "", meetLink.value = ""
32. CoroutineScope(Dispatchers.Main).launch { ... }
33. GmailService(credential) instantiated
34. gmailService.fetchRecentEmails(10) called (suspend, runs on Dispatchers.IO)
35. gmail.users().messages().list("me").setMaxResults(10).setLabelIds(["INBOX"]).execute()
36. → Returns 10 message references (IDs only)
37. For each message ID:
38.   gmail.users().messages().get("me", id).setFormat("metadata")
        .setMetadataHeaders(["Subject","From","Date"]).execute()
39.   Extract Subject, From, Date headers + snippet
40.   Create EmailMessage(id, subject, snippet, from, date)
41. Returns List<EmailMessage> with 10 items
```

**Phase 4 — Agent Analysis**

```
42. EmailAnalyzer.summarize(emails) → 10 summary strings
43. MeetingDetector.isMeetingRequired(emails)
      → scans all 10 emails for keywords → true (e.g., email #3 contains "meeting")
44. MeetingDetector.detectMeetingEmails(emails) → [email #3, email #7]
45. TimeExtractor.extractTimesFromEmails([email #3, email #7])
      → regex on subject+snippet → ["Tomorrow at 10:30", "3 pm"]
46. ParticipantExtractor.extractFromEmails([email #3, email #7])
      → regex on from+subject+snippet → ["alice@example.com", "bob@company.org"]
```

**Phase 5 — UI Update**

```
47. extractedSlots.value = ["Tomorrow at 10:30", "3 pm"]
48. extractedParticipants.value = ["alice@example.com", "bob@company.org"]
49. StringBuilder builds formatted result:
      "📋 Summaries:
       1. Team sync — Let's discuss the project timeline...
       2. ...
       📅 Meeting Required!
       Proposed Slots:
         • Tomorrow at 10:30
         • 3 pm
       👥 Detected Participants:
         • alice@example.com
         • bob@company.org"
50. resultText.value = formatted string
51. Compose recomposition triggered → UI updates
52. assistantSpeaker.speak("Inbox scanned. 10 emails found. 2 emails suggest meetings.")
53. isLoading.value = false
```

**Total code path:** 53 discrete steps across 8 classes.

---

## 12. FUTURE EXTENSIONS

Based on the current architecture, the following improvements could enhance the
assistant's capabilities.

### 12.1 Smarter NLP with On-Device LLM

**Current state:** Keyword matching in `MeetingDetector` and regex in
`TimeExtractor` / `ParticipantExtractor`.

**Improvement:** Integrate an on-device LLM (e.g., Google's Gemini Nano via
ML Kit, or a quantized model via ONNX Runtime / llama.cpp) to:
- Classify email intent beyond keyword matching (urgency, sentiment, topic).
- Generate more natural summaries instead of subject+snippet truncation.
- Parse complex time expressions ("next week sometime", "the day after tomorrow
  in the afternoon").
- Understand context across multiple emails in a thread.

### 12.2 Smarter Scheduling with Availability Checking

**Current state:** `CalendarService.getFreeSlots()` exists but is not yet
connected to the scheduling flow.

**Improvement:** Before creating a meeting, automatically call `getFreeSlots()`
and present available alternatives to the user if the proposed time conflicts.
The voice assistant could say: "You have a conflict at 3 PM. Would you like
5:30 PM instead?"

### 12.3 Better Participant Detection

**Current state:** Regex extraction of email addresses from the `from`, `subject`,
and `snippet` fields only.

**Improvement:**
- Fetch full email body (not just snippet) for more addresses.
- Parse CC and BCC headers.
- Maintain a contacts database mapping names to email addresses.
- Use NLP to resolve references like "invite the design team" to a group of
  addresses.

### 12.4 Multi-Turn Voice Conversations

**Current state:** One-shot voice command → action → spoken response.

**Improvement:** Implement a conversation state machine:
```
User: "Schedule a meeting"
Assistant: "When would you like the meeting?"
User: "Tomorrow at 3 PM"
Assistant: "Who should I invite?"
User: "Alice and Bob"
Assistant: "Created a meeting tomorrow at 3 PM with Alice and Bob."
```

This requires maintaining conversation context between voice turns.

### 12.5 Email Thread Grouping

**Current state:** Each email is analyzed independently.

**Improvement:** Group emails by thread ID (available in Gmail API metadata),
summarize entire conversation threads, and detect meeting intent at the thread
level rather than per-message.

### 12.6 Smart Notification Actions

**Current state:** `InboxMonitorWorker` shows a basic notification.

**Improvement:** Add notification action buttons: "Scan Now" and "Schedule
Meeting" that deep-link back into the app and trigger the respective actions
directly.

### 12.7 Calendar Widget

**Improvement:** Add an Android home screen widget that displays the daily
briefing summary and updates periodically, so the user can see their schedule
without opening the app.

### 12.8 Offline Mode

**Current state:** All operations require network access to Google APIs.

**Improvement:** Cache the last inbox scan results and calendar events locally
(Room database). Allow the agent logic to operate on cached data when offline,
and sync when connectivity is restored.

### 12.9 Migration to Credential Manager

**Current state:** Uses the deprecated `GoogleSignIn` API.

**Improvement:** Migrate to Android's Credential Manager API (`androidx.credentials`)
which is Google's recommended replacement. This also enables passkey support and
a more streamlined authentication UX.

---

*End of document.*

