/* Architecture explorer, pipelines, build guide and known issues. Content taken from the Kotlin sources. */
(function () {
  "use strict";
  function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
  function ico(id) { return '<svg aria-hidden="true"><use href="#' + id + '"/></svg>'; }

  /* ---------- architecture ---------- */
  var LAYERS = [
    { key: "ui", icon: "i-bot", name: "UI", sub: "Jetpack Compose, Material 3", text: "A single stateless composable draws the whole screen from state passed in, and sends taps back as lambdas.",
      files: [["MainActivity.kt — AgentScreen", "Sign-in card, voice card, action cards, results and the Meet link card. ResultSections() splits the result text on blank lines and gives known headings an icon.", ["AgentScreen()", "ActionCard()", "ResultSections()", "parseSectionContent()"]],
        ["ui/theme/", "A calm blue-grey Material 3 palette with a matching dark theme, chosen from the system setting.", ["Color.kt", "Theme.kt", "Type.kt"]]] },
    { key: "controller", icon: "i-gear", name: "Controller", sub: "MainActivity", text: "Holds all state in mutableStateOf fields, routes voice commands and runs each pipeline in a coroutine.",
      files: [["MainActivity.kt", "1,154 lines: state, sign-in launchers, the smart scheduling router, scanInbox, scheduleMeeting, dailyBriefing and the time parser.", ["routeVoiceCommand()", "smartScheduleMeeting()", "scanInbox()", "scanInboxThenSchedule()", "scheduleMeeting()", "dailyBriefing()", "resolveStartTime()", "applyTimeToCal()"]]] },
    { key: "voice", icon: "i-mic", name: "Voice", sub: "SpeechRecognizer + TextToSpeech", split: true, text: "Speech in, speech out, both from Android itself.",
      files: [["voice/VoiceCommandManager.kt", "Wraps SpeechRecognizer (free-form, one result) and maps the text to SCAN_INBOX, SCHEDULE_MEETING, DAILY_BRIEFING or UNKNOWN by phrase matching.", ["startListening()", "parseCommand()", "stopListening()", "destroy()"]],
        ["voice/AssistantSpeaker.kt", "Wraps TextToSpeech in US English. Each new message interrupts the last (QUEUE_FLUSH).", ["speak()", "stop()", "shutdown()"]]] },
    { key: "auth", icon: "i-lock", name: "Auth", sub: "Google Sign-In", split: true, text: "Signs in with Google and hands out credentials for the API clients.",
      files: [["auth/GoogleAuthManager.kt", "Requests gmail.readonly and calendar.events, builds a GoogleAccountCredential, and turns status codes like 10 (DEVELOPER_ERROR) into readable messages.", ["getSignInIntent()", "handleSignInResult()", "isSignedIn()", "getCredential()", "signOut()", "revokeAccess()"]]] },
    { key: "agent", icon: "i-stats", name: "Agent logic", sub: "Plain Kotlin rules", split: true, text: "Five small objects that do all the “thinking” with keyword lists and regular expressions.",
      files: [["agent/EmailAnalyzer.kt", "One line per email: subject, a dash, and the first 120 characters of the snippet.", ["summarize()"]],
        ["agent/MeetingDetector.kt", "Flags an email if its subject or snippet contains any of 11 keywords, as plain substrings.", ["isMeetingRequired()", "detectMeetingEmails()"]],
        ["agent/TimeExtractor.kt", "Three regexes for “5 pm”, “tomorrow at 4” and “Tuesday 10:30 am”, de-duplicated in order.", ["extractTimes()", "extractTimesFromEmails()"]],
        ["agent/ParticipantExtractor.kt", "Collects every email address in the sender, subject and snippet.", ["extractFromEmail()", "extractFromEmails()"]],
        ["agent/DailyBriefingGenerator.kt", "Turns today's events into one spoken paragraph.", ["generate()"]]] },
    { key: "service", icon: "i-cloud", name: "Google APIs", sub: "Gmail + Calendar REST", text: "Thin wrappers over the official Java client libraries, called on Dispatchers.IO.",
      files: [["service/GmailService.kt", "Lists the newest inbox messages and fetches Subject, From, Date and the snippet for each. Failed messages are skipped quietly.", ["fetchRecentEmails()"]],
        ["service/CalendarService.kt", "Creates 30-minute events with a Google Meet link (hangoutsMeet), optionally with attendees and email invites, lists today's events and finds free half-hour slots.", ["createMeetingEvent()", "createMeetingEventWithAttendees()", "getTodayEvents()", "getFreeSlots()"]]] },
    { key: "worker", icon: "i-bell", name: "Background worker", sub: "WorkManager, every 15 min", text: "Keeps watching the inbox when the app is closed.",
      files: [["worker/InboxMonitorWorker.kt", "A CoroutineWorker scheduled with KEEP policy. Fetches the 5 newest emails and, if any look like meeting requests, posts notification 1001 on the Inbox Monitor channel. Retries on errors.", ["doWork()", "showNotification()"]]] },
  ];
  var layersEl = document.getElementById("layers"), detailEl = document.getElementById("layer-detail");
  var cur = "agent";
  function layerBtn(l) {
    return '<button class="layer" type="button" data-l="' + l.key + '" aria-pressed="' + (l.key === cur) + '"><span class="ico">' + ico(l.icon) + "</span><span><b>" + l.name + "</b><span>" + l.sub + "</span></span></button>";
  }
  function drawLayers() {
    var by = {}; LAYERS.forEach(function (l) { by[l.key] = l; });
    layersEl.innerHTML = layerBtn(by.ui) + '<div class="arrow">↓ taps and voice</div>' + layerBtn(by.controller) + '<div class="arrow">↓ calls</div>' +
      '<div class="layer-split">' + layerBtn(by.voice) + layerBtn(by.auth) + layerBtn(by.agent) + "</div>" + '<div class="arrow">↓ credentials and data</div>' +
      layerBtn(by.service) + '<div class="arrow">↕ also used by</div>' + layerBtn(by.worker);
    var l = by[cur];
    detailEl.innerHTML = "<h3>" + l.name + "</h3><p>" + esc(l.text) + "</p>" + l.files.map(function (f) {
      return '<div class="file"><h4>' + esc(f[0]) + "</h4><p>" + esc(f[1]) + '</p><div class="fns">' + f[2].map(function (x) { return "<code>" + esc(x) + "</code>"; }).join("") + "</div></div>";
    }).join("");
    layersEl.querySelectorAll("[data-l]").forEach(function (b) { b.addEventListener("click", function () { cur = b.dataset.l; drawLayers(); }); });
  }
  drawLayers();

  /* ---------- pipelines ---------- */
  var PIPES = [
    { key: "voice", name: "Voice", intro: "From a tap on the mic to a spoken reply.", steps: [
      ["Permission", "If RECORD_AUDIO isn't granted yet, Android asks first.", "requestVoiceInput()"],
      ["Listen", "SpeechRecognizer starts in free-form mode with one result. The button turns to “Listening…”.", "VoiceCommandManager.startListening()"],
      ["Classify", "The text is lowercased and checked against fixed phrases such as “check my inbox” or “what's on my calendar”.", "parseCommand()"],
      ["Route", "Scan and briefing run straight away. Scheduling goes through the smart router, which scans the inbox first if no time was found yet.", "routeVoiceCommand(), smartScheduleMeeting()"],
      ["Reply", "AssistantSpeaker reads the result. Each new message cuts off the previous one, so quick replies can interrupt each other.", "AssistantSpeaker.speak()"]] },
    { key: "scan", name: "Scan inbox", intro: "What “check inbox” does with your 10 newest emails.", steps: [
      ["Credential", "Builds a GoogleAccountCredential with the two scopes, or asks you to sign in.", "GoogleAuthManager.getCredential()"],
      ["Fetch", "Lists 10 inbox messages and reads Subject, From, Date and the snippet of each.", "GmailService.fetchRecentEmails(10)"],
      ["Summarise", "One line per email, the snippet cut at 120 characters.", "EmailAnalyzer.summarize()"],
      ["Detect meetings", "Keeps emails containing any of 11 keywords. Matching is by substring, so “recall” and “async” count too.", "MeetingDetector.detectMeetingEmails()", true],
      ["Find times and people", "Regexes pull times and addresses out of the meeting emails only.", "TimeExtractor, ParticipantExtractor"],
      ["Show and speak", "Results appear in titled sections, the summary is spoken, and an “Assistant Insight” block explains each meeting email in words. Despite its name, speakMeetingExplanation() only writes that text.", "speakMeetingExplanation()", true]] },
    { key: "schedule", name: "Schedule", intro: "What “schedule a meeting” does with the first time it found.", steps: [
      ["Pick a time", "Takes the first proposed slot and turns it into a date. Without am or pm the hour is read as morning, so “tomorrow at 4” becomes 4:00 AM.", "resolveStartTime(), applyTimeToCal()", true],
      ["Find free slots", "Looks for free half-hour slots from that time over the next 24 hours, labelled like “5:00 PM” or “Tomorrow 10:00 AM”. Any day other than today is labelled “Tomorrow”.", "CalendarService.getFreeSlots()", true],
      ["Check for a clash", "Compares the proposed label with the free-slot labels as text. “5 pm” never equals “5:00 PM”, so it usually reports the time as booked, even when the “next available slot” it offers is the same time.", "scheduleMeeting()", true],
      ["Create the event", "A 30-minute event titled “Meeting via AgenticAssistant” with a Google Meet link. If addresses were found, they're added as attendees and Google emails them invitations straight away.", "createMeetingEventWithAttendees()", true],
      ["Confirm", "The Meet link appears under “Meeting Created!” and the result is spoken.", "meetLink, AssistantSpeaker.speak()"]] },
    { key: "briefing", name: "Daily briefing", intro: "What “daily briefing” says.", steps: [
      ["Fetch today", "All events on the primary calendar from midnight to 23:59, recurring ones expanded, in start order.", "CalendarService.getTodayEvents()"],
      ["Write it", "“Today you have 3 meetings. 1. Team standup at 9:30 AM…”, or that your calendar is clear.", "DailyBriefingGenerator.generate()"],
      ["Show and speak", "Shown under a Daily Briefing heading and read aloud in full.", "dailyBriefing()"]] },
    { key: "monitor", name: "Background", intro: "What happens while the app is closed.", steps: [
      ["Schedule", "On launch, a periodic job is registered every 15 minutes, WorkManager's minimum. KEEP means relaunching doesn't reset it.", "scheduleInboxMonitor()"],
      ["Check", "If you're signed in, fetch the 5 newest emails and run the meeting detector.", "InboxMonitorWorker.doWork()"],
      ["Notify", "“Meeting request detected: N emails suggest a meeting.” The notification has a fixed ID, so a new one replaces the last. It doesn't remember what it already told you, so the same emails can trigger it every 15 minutes.", "showNotification()", true],
      ["Retry", "On any error the job returns Result.retry() and WorkManager tries again later.", "Result.retry()"]] },
  ];
  var tabs = document.getElementById("pipe-tabs"), pipe = document.getElementById("pipe"), curPipe = "scan";
  function drawPipe() {
    tabs.innerHTML = PIPES.map(function (p) { return '<button type="button" role="tab" data-p="' + p.key + '" aria-selected="' + (p.key === curPipe) + '">' + p.name + "</button>"; }).join("");
    var p = PIPES.find(function (x) { return x.key === curPipe; });
    pipe.innerHTML = "<p>" + esc(p.intro) + '</p><ol class="steps">' + p.steps.map(function (s) {
      return '<li class="' + (s[3] ? "bug" : "") + '"><b>' + esc(s[0]) + "</b><span>" + esc(s[1]) + '</span><span class="ref">' + esc(s[2]) + "</span></li>";
    }).join("") + "</ol>";
    tabs.querySelectorAll("[data-p]").forEach(function (b) { b.addEventListener("click", function () { curPipe = b.dataset.p; drawPipe(); }); });
  }
  drawPipe();

  /* ---------- build ---------- */
  document.getElementById("build-grid").innerHTML =
    '<div class="card"><h3>1. Google Cloud</h3><ol>' +
    "<li>Create a project in the Google Cloud console and enable the <b>Gmail API</b> and <b>Google Calendar API</b>.</li>" +
    "<li>Set up the OAuth consent screen as External, in Testing, and add your Google account as a test user.</li>" +
    "<li>Create an OAuth client of type <b>Android</b> for package <code>com.example.agenticassistant</code> with your debug SHA-1.</li>" +
    "<li>Sign-in fails with status 10 (DEVELOPER_ERROR) until the package name and SHA-1 match exactly.</li></ol>" +
    '<div class="cmd"><pre id="c-sha">./gradlew signingReport</pre><button class="copy" type="button" data-copy="c-sha">Copy</button></div>' +
    '<div class="scopes"><div class="scope"><code>https://www.googleapis.com/auth/gmail.readonly</code>Read your mail, never send or delete.</div><div class="scope"><code>https://www.googleapis.com/auth/calendar.events</code>Read and create events on your calendars.</div></div></div>' +
    '<div class="card"><h3>2. Android Studio</h3><ol>' +
    "<li>Open the project in a recent Android Studio. It targets SDK 36 with a minimum of 26 (Android 8.0).</li>" +
    "<li>Run on a device or an emulator with Google Play services, signed in to your test account.</li>" +
    "<li>Allow the microphone when you first tap Voice Command, and notifications on Android 13 and later.</li>" +
    "<li>Tap Sign in with Google, then try “check my inbox”.</li></ol>" +
    '<div class="cmd"><pre id="c-run">git clone https://github.com/SparshSunilNaik/AI-Assistant-PoC.git\ncd AI-Assistant-PoC\n./gradlew installDebug</pre><button class="copy" type="button" data-copy="c-run">Copy</button></div>' +
    '<p class="note">Permissions in the manifest: INTERNET, RECORD_AUDIO and POST_NOTIFICATIONS.</p></div>';

  /* ---------- issues ---------- */
  var ISSUES = [
    ["False alarms from substrings", "Keywords match inside other words, so “please recall the invoice” and “async jobs” count as meeting requests. Matching whole words would fix it.", "Scan the sample inbox", function () { window.Phone.run("check my inbox"); }],
    ["Every clash check says “booked”", "The proposed time is compared to free slots as text (“5 pm” vs “5:00 PM”), so the app usually announces a conflict and offers the very same time. Comparing timestamps would fix it.", "Schedule from the inbox", function () { window.Phone.run("schedule a meeting"); }],
    ["“Tomorrow at 4” means 4 AM", "When a time has no am or pm, applyTimeToCal() keeps the hour as is, so most afternoon meetings land before dawn. Treating 1 to 7 as pm is a common fix.", "Book “tomorrow at 4”", function () { window.Phone.run("schedule a meeting"); }],
    ["Invites go out without asking", "Every address found in a meeting email is invited and Google emails them at once, including billing and bot addresses. A confirm step before sending would help.", null],
    ["“Meeting Created!” on errors", "The link card shows whenever its text isn't empty, so “Please sign in first.” or “No free slots found” appear under a Meeting Created heading.", null],
    ["Repeat notifications", "The monitor doesn't remember which emails it has already reported, so the same meeting request can notify you every 15 minutes.", "Fast-forward twice", function () { window.Phone.fastForward().then(function () { setTimeout(window.Phone.fastForward, 1500); }); }],
  ];
  var issuesEl = document.getElementById("issues");
  issuesEl.innerHTML = ISSUES.map(function (x, i) {
    return '<div class="card issue"><div class="n">' + (i + 1) + "</div><h3>" + esc(x[0]) + "</h3><p>" + esc(x[1]) + "</p>" + (x[2] ? '<button class="btn small tonal try" type="button" data-i="' + i + '">' + esc(x[2]) + "</button>" : "") + "</div>";
  }).join("");
  issuesEl.querySelectorAll("[data-i]").forEach(function (b) {
    b.addEventListener("click", function () {
      document.getElementById("simulator").scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
      if (!window.Phone.state().isSignedIn) window.Phone.signIn();
      setTimeout(ISSUES[Number(b.dataset.i)][3], 600);
    });
  });

  /* ---------- copy buttons ---------- */
  document.addEventListener("click", function (e) {
    var b = e.target.closest("[data-copy]");
    if (!b) return;
    var text = document.getElementById(b.dataset.copy).textContent;
    function done(ok) { b.textContent = ok ? "Copied" : "Press Ctrl+C"; setTimeout(function () { b.textContent = "Copy"; }, 1400); }
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
    else done(false);
  });
})();
