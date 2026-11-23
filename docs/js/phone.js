/* The phone: a port of MainActivity (state, voice routing, scanInbox, scheduleMeeting, dailyBriefing,
   the smart router, InboxMonitorWorker) and the AgentScreen composable, running on simulated data. */
(function () {
  "use strict";
  var A = window.Agent, D = window.SimData;
  var reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---------- shared mailbox (edited by inbox.js) ---------- */
  var Mailbox = {
    emails: D.INBOX.map(function (e) { return Object.assign({ on: true }, e); }),
    listeners: [],
    active: function () { return this.emails.filter(function (e) { return e.on; }); },
    changed: function () { this.listeners.forEach(function (f) { f(); }); },
    reset: function () { this.emails = D.INBOX.map(function (e) { return Object.assign({ on: true }, e); }); this.changed(); },
  };
  window.Mailbox = Mailbox;

  /* ---------- state (MainActivity's mutableStateOf fields) ---------- */
  var S;
  function freshState() {
    return {
      isSignedIn: false, extractedSlots: [], extractedParticipants: [], resultText: "", meetLink: "",
      isLoading: false, isListening: false, voiceStatus: "",
      clock: D.START, calendar: D.CALENDAR.map(function (e) { return Object.assign({}, e); }), arrivals: 0, run: 0,
    };
  }
  S = freshState();
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, reduce ? 0 : ms); }); }
  function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }

  /* ---------- logcat ---------- */
  var logEl = document.getElementById("logcat");
  function log(level, tag, msg) {
    var d = new Date(S.clock), t = String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
    var line = document.createElement("div");
    line.className = level;
    line.textContent = t + "  " + level + "/" + tag + ": " + msg;
    logEl.appendChild(line);
    while (logEl.children.length > 300) logEl.removeChild(logEl.firstChild);
    logEl.scrollTop = logEl.scrollHeight;
  }
  document.getElementById("log-clear").addEventListener("click", function () { logEl.textContent = ""; });

  /* ---------- AssistantSpeaker (TextToSpeech, QUEUE_FLUSH) ---------- */
  var ttsToggle = document.getElementById("tts-toggle");
  var cap = document.getElementById("ph-caption"), capText = document.getElementById("ph-caption-text"), capT;
  function speak(message) {
    log("S", "AssistantSpeaker", "speak(\"" + message + "\")");
    capText.textContent = message;
    cap.classList.add("show");
    clearTimeout(capT);
    var dur = 1400 + message.length * 55;
    if (ttsToggle.checked && "speechSynthesis" in window) {
      speechSynthesis.cancel(); // QUEUE_FLUSH interrupts whatever is being said
      var u = new SpeechSynthesisUtterance(message);
      u.lang = "en-US"; // Locale.US
      u.onend = function () { cap.classList.remove("show"); };
      speechSynthesis.speak(u);
      dur += 4000;
    }
    capT = setTimeout(function () { cap.classList.remove("show"); }, dur);
  }

  /* ---------- rendering: AgentScreen ---------- */
  var content = document.getElementById("ph-content"), logoutBtn = document.getElementById("ph-logout");
  function icon(id) { return '<svg aria-hidden="true"><use href="#' + id + '"/></svg>'; }
  function parseSection(text) {
    var lines = text.split("\n"), first = (lines[0] || "").trim(), ic;
    var strip = function (s) { return s.replace(/^[^A-Za-z]+/, "").trim(); };
    if (/summaries/i.test(first)) ic = "i-mail";
    else if (/meeting required/i.test(first) || /scheduling/i.test(first)) ic = "i-cal";
    else if (/daily briefing/i.test(first)) ic = "i-stats";
    else if (/assistant insight/i.test(first)) ic = "i-bot";
    else if (/participants/i.test(first)) ic = null;
    else if (first.slice(-1) === ":" && lines.length > 1) ic = null;
    else return { title: null, body: text };
    return { icon: ic, title: strip(first), body: lines.slice(1).join("\n").trim() };
  }
  function resultSections(text) {
    return text.split("\n\n").filter(function (s) { return s.trim(); }).map(function (sec) {
      var p = parseSection(sec.trim());
      if (p.title == null) return '<div class="r-sec"><div class="r-body">' + esc(sec.trim()) + "</div></div>";
      return '<div class="r-sec"><div class="r-title">' + (p.icon ? icon(p.icon) : "") + esc(p.title) + "</div>" + (p.body ? '<div class="r-body">' + esc(p.body) + "</div>" : "") + "</div>";
    }).join("");
  }
  function render() {
    var h = "";
    if (!S.isSignedIn) {
      h += '<div class="m-card pc center">' + '<span class="welcome-ico">' + icon("i-bot") + "</span>" +
        "<h3>Welcome</h3><p>Sign in to access your inbox, schedule meetings, and get daily briefings.</p>" +
        '<button class="m-btn" type="button" data-act="signin" style="margin-top:6px">Sign in with Google</button></div>';
    } else {
      h += '<div class="m-card voice' + (S.isListening ? " listening" : "") + '">' +
        '<button class="m-btn' + (S.isListening ? " listening" : "") + '" type="button" data-act="voice"' + (S.isListening || S.isLoading ? " disabled" : "") + ">" + icon("i-mic") + (S.isListening ? "Listening…" : "Voice Command") + "</button>" +
        (S.voiceStatus ? '<div class="voice-status">' + esc(S.voiceStatus) + "</div>" : "") + "</div>";
      h += '<div class="m-row"><button class="m-btn m-tonal" type="button" data-act="scan"' + (S.isLoading ? " disabled" : "") + ">" + icon("i-mail") + "Scan Inbox</button>" +
        '<button class="m-btn m-tonal" type="button" data-act="schedule">' + icon("i-cal") + "Schedule</button></div>" +
        '<button class="m-btn m-tonal" type="button" data-act="briefing"' + (S.isLoading ? " disabled" : "") + ">" + icon("i-stats") + "Daily Briefing</button>";
    }
    if (S.isLoading) h += '<div class="spinner" role="status" aria-label="Loading"></div>';
    if (S.resultText) h += '<div class="m-card">' + resultSections(S.resultText) + "</div>";
    if (S.meetLink) {
      var isLink = S.meetLink.indexOf("https://") === 0;
      h += '<div class="m-card meet"><div class="r-title">' + icon("i-video") + "Meeting Created!</div>" +
        '<div class="link">' + esc(S.meetLink) + "</div>" + (isLink ? '<div class="sim-note">Simulated link, not a real meeting</div>' : "") + "</div>";
    }
    content.innerHTML = h;
    logoutBtn.hidden = !S.isSignedIn;
    var d = new Date(S.clock);
    document.getElementById("sb-time").textContent = d.getHours() + ":" + String(d.getMinutes()).padStart(2, "0");
    document.getElementById("clock").textContent = d.toLocaleDateString("en-US", { weekday: "short" }) + " " + A.fmtClock(S.clock);
  }
  content.addEventListener("click", function (e) {
    var b = e.target.closest("[data-act]");
    if (!b || b.disabled) return;
    var a = b.dataset.act;
    if (a === "signin") signIn();
    else if (a === "voice") requestVoiceInput();
    else if (a === "scan") scanInbox();
    else if (a === "schedule") scheduleMeeting();   // the button calls scheduleMeeting() directly
    else if (a === "briefing") dailyBriefing();
  });
  logoutBtn.addEventListener("click", logout);

  /* ---------- auth ---------- */
  function signIn() {
    log("D", "MainActivity", "Sign-in button pressed");
    log("D", "GoogleAuthManager", "Requesting scopes gmail.readonly, calendar.events");
    log("I", "GoogleAuthManager", "Signed in as " + D.ACCOUNT + " (simulated)");
    S.isSignedIn = true;
    render();
  }
  function logout() {
    log("D", "MainActivity", "Logout button pressed");
    var c = S.clock, cal = S.calendar, arr = S.arrivals;
    S = freshState(); S.clock = c; S.calendar = cal; S.arrivals = arr;
    log("D", "MainActivity", "Logged out — state cleared");
    render();
  }

  /* ---------- voice ---------- */
  var micToggle = document.getElementById("mic-toggle");
  var Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  function requestVoiceInput(spoken) {
    if (!S.isSignedIn) signIn();
    if (S.isListening || S.isLoading) return;
    S.isListening = true; S.voiceStatus = "🎤 Listening..."; render();
    log("D", "VoiceCommandManager", "startListening() LANGUAGE_MODEL_FREE_FORM, maxResults=1");
    if (spoken == null && micToggle.checked && Recognition) {
      var rec = new Recognition();
      rec.lang = "en-US"; rec.maxAlternatives = 1; rec.interimResults = false;
      rec.onresult = function (ev) { onVoiceResult(ev.results[0][0].transcript); };
      rec.onerror = function (ev) {
        var msg = ev.error === "no-speech" ? "No speech detected — try again" : ev.error === "not-allowed" ? "Insufficient permissions — grant RECORD_AUDIO" : "Client side error";
        S.isListening = false; S.voiceStatus = "⚠️ " + msg; render(); speak(msg);
      };
      rec.onend = function () { if (S.isListening) { S.isListening = false; render(); } };
      try { rec.start(); } catch (e) { S.isListening = false; render(); }
      return;
    }
    var text = spoken != null ? spoken : (document.getElementById("say-input").value.trim() || "check inbox");
    setTimeout(function () { onVoiceResult(text); }, reduce ? 0 : 1300);
  }
  function onVoiceResult(rawText) {
    var parsed = A.parseCommand(rawText);
    S.isListening = false;
    S.voiceStatus = "Heard: \"" + rawText + "\"";
    log("D", "MainActivity", "Voice command: " + rawText + " → " + parsed.command);
    showParse(rawText, parsed);
    render();
    routeVoiceCommand(parsed.command, rawText);
  }
  function routeVoiceCommand(command, rawText) {
    if (command === "SCAN_INBOX") { speak("Scanning your inbox now."); scanInbox(); }
    else if (command === "SCHEDULE_MEETING") smartScheduleMeeting();
    else if (command === "DAILY_BRIEFING") { speak("Getting your daily briefing."); dailyBriefing(); }
    else {
      var msg = "I didn't understand \"" + rawText + "\". Try saying check inbox, schedule meeting, or daily briefing.";
      S.voiceStatus = msg; render(); speak(msg);
    }
  }

  /* ---------- smart router ---------- */
  function smartScheduleMeeting() {
    if (!S.isSignedIn) { S.meetLink = "Please sign in first."; render(); speak("Please sign in first."); return; }
    if (!S.extractedSlots.length) {
      speak("Checking your inbox for meeting suggestions.");
      log("D", "MainActivity", "smartScheduleMeeting: No slots available, scanning inbox first");
      scanInboxThenSchedule();
    } else {
      speak("Scheduling meeting using detected time.");
      log("D", "MainActivity", "smartScheduleMeeting: Using existing slot: " + S.extractedSlots[0]);
      scheduleMeeting();
    }
  }

  /* ---------- Gmail fetch ---------- */
  async function fetchRecentEmails(n, run) {
    log("D", "GmailService", "users().messages().list(\"me\") labelIds=INBOX maxResults=" + n);
    await sleep(700);
    var emails = Mailbox.active().slice(0, n);
    log("D", "GmailService", "Fetched " + emails.length + " messages (format=metadata: Subject, From, Date)");
    return emails;
  }
  function analyse(emails) {
    var meetingEmails = A.MeetingDetector.detectMeetingEmails(emails);
    meetingEmails.forEach(function (e) { log("D", "MeetingDetector", "“" + e.subject + "” matched " + A.MeetingDetector.matchedKeywords(e).map(function (k) { return "\"" + k + "\""; }).join(", ")); });
    var slots = A.TimeExtractor.extractTimesFromEmails(meetingEmails);
    var participants = A.ParticipantExtractor.extractFromEmails(meetingEmails);
    log("D", "TimeExtractor", "Proposed slots: [" + slots.join(", ") + "]");
    log("D", "ParticipantExtractor", "Participants: [" + participants.join(", ") + "]");
    return { summaries: A.EmailAnalyzer.summarize(emails), meetingEmails: meetingEmails, slots: slots, participants: participants };
  }

  /* ---------- scanInbox ---------- */
  async function scanInbox() {
    if (!S.isSignedIn) { S.resultText = "Please sign in first."; render(); speak("Please sign in first."); return; }
    var run = S.run;
    log("D", "MainActivity", "scanInbox()");
    S.isLoading = true; S.resultText = ""; S.meetLink = ""; render();
    try {
      var emails = await fetchRecentEmails(10, run);
      if (run !== S.run) return;
      if (!emails.length) { S.resultText = "No emails found."; speak("No emails found in your inbox."); S.isLoading = false; render(); return; }
      var r = analyse(emails);
      var meetingRequired = A.MeetingDetector.isMeetingRequired(emails);
      S.extractedSlots = r.slots; S.extractedParticipants = r.participants;
      var sb = "📋 Summaries:\n";
      r.summaries.forEach(function (s, i) { sb += (i + 1) + ". " + s + "\n"; });
      if (meetingRequired) {
        sb += "\n📅 Meeting Required!\n";
        if (r.slots.length) { sb += "Proposed Slots:\n"; r.slots.forEach(function (s) { sb += "  • " + s + "\n"; }); }
        else sb += "No specific times found in emails.\n";
        if (r.participants.length) { sb += "\n👥 Detected Participants:\n"; r.participants.forEach(function (p) { sb += "  • " + p + "\n"; }); }
      }
      S.resultText = sb;
      speak("Inbox scanned. " + r.summaries.length + " emails found." + (meetingRequired ? " " + r.meetingEmails.length + " emails suggest meetings." : ""));
      speakMeetingExplanation(r.meetingEmails, r.slots, r.participants);
    } finally {
      if (run === S.run) { S.isLoading = false; render(); }
    }
  }

  /* ---------- scanInboxThenSchedule ---------- */
  async function scanInboxThenSchedule() {
    var run = S.run;
    S.isLoading = true; S.resultText = ""; S.meetLink = ""; render();
    try {
      var emails = await fetchRecentEmails(10, run);
      if (run !== S.run) return;
      if (!emails.length) { S.resultText = "No emails found."; speak("No emails found in your inbox."); S.isLoading = false; render(); return; }
      var r = analyse(emails);
      S.extractedSlots = r.slots; S.extractedParticipants = r.participants;
      var sb = "📋 Summaries:\n";
      r.summaries.forEach(function (s, i) { sb += (i + 1) + ". " + s + "\n"; });
      if (r.meetingEmails.length) {
        sb += "\n📅 Meeting Required!\n";
        if (r.slots.length) { sb += "Proposed Slots:\n"; r.slots.forEach(function (s) { sb += "  • " + s + "\n"; }); }
        if (r.participants.length) { sb += "👥 Detected Participants:\n"; r.participants.forEach(function (p) { sb += "  • " + p + "\n"; }); }
      }
      S.resultText = sb;
      speakMeetingExplanation(r.meetingEmails, r.slots, r.participants);
      S.isLoading = false; render();
      if (r.slots.length) { speak("Scheduling meeting using detected time."); scheduleMeeting(); }
      else speak("I could not find a meeting time in your inbox.");
    } finally {
      if (run === S.run) { S.isLoading = false; render(); }
    }
  }

  /* ---------- speakMeetingExplanation (despite the name, it only appends text) ---------- */
  function speakMeetingExplanation(meetingEmails, slots, participants) {
    if (!meetingEmails.length) return;
    var ex = [];
    meetingEmails.forEach(function (email) {
      var m = /^([^<]+)/.exec(email.from);
      var sender = m ? (m[1].trim() || email.from) : email.from;
      var es = A.TimeExtractor.extractTimes(email.subject + " " + email.snippet);
      var slotInfo = es.length ? " suggesting " + es[0] : slots.length ? " suggesting " + slots[0] : "";
      ex.push("I found a meeting email from " + sender + slotInfo + ".");
    });
    if (participants.length) {
      var who = participants.length <= 3 ? participants.join(" and ") : participants.slice(0, 2).join(", ") + " and " + (participants.length - 2) + " others";
      ex.push("I detected a meeting request involving " + who + ".");
    }
    S.resultText += "\n\n🤖 Assistant Insight:\n" + ex.join("\n");
    log("D", "MainActivity", "Meeting explanation: [" + ex.join(", ") + "]");
  }

  /* ---------- scheduleMeeting ---------- */
  async function scheduleMeeting() {
    if (!S.isSignedIn) { S.meetLink = "Please sign in first."; render(); speak("Please sign in first."); return; }
    var run = S.run;
    var cal = new A.CalendarService(S.calendar, S.clock);
    var startTimeMillis = A.resolveStartTime(S.extractedSlots[0] == null ? null : S.extractedSlots[0], S.clock);
    var attendees = S.extractedParticipants;
    log("D", "MainActivity", "resolveStartTime(" + (S.extractedSlots[0] ? "\"" + S.extractedSlots[0] + "\"" : "null") + ") → " + new Date(startTimeMillis).toLocaleString("en-US", { weekday: "short", hour: "numeric", minute: "2-digit" }));
    log("D", "MainActivity", "Checking calendar conflicts for proposed time...");
    await sleep(500);
    if (run !== S.run) return;
    var freeSlots = cal.getFreeSlots(startTimeMillis);
    log("D", "CalendarService", "getFreeSlots() → [" + freeSlots.join(", ") + "]");
    if (freeSlots.length) {
      var first = freeSlots[0], proposed = S.extractedSlots[0];
      if (proposed != null && first !== proposed) {
        var proposedIsFree = freeSlots.some(function (s) { return s.toLowerCase() === proposed.toLowerCase(); });
        if (!proposedIsFree) {
          var msg = "That time is already booked. Your next available slot is " + first + ".";
          log("D", "MainActivity", "Conflict detected: " + msg);
          speak(msg);
          S.resultText += "\n\n⚠️ " + msg;
          startTimeMillis = A.resolveStartTime(first, S.clock);
          S.extractedSlots = [first].concat(S.extractedSlots.slice(1));
        }
      }
    } else {
      var none = "No free slots found in the next 24 hours.";
      log("W", "MainActivity", none); speak(none); S.meetLink = none; render(); return;
    }
    log("D", "CalendarService", "events().insert(\"primary\") conferenceDataVersion=1" + (attendees.length ? " sendUpdates=all attendees=" + attendees.length : ""));
    await sleep(600);
    if (run !== S.run) return;
    var result = cal.createMeetingEvent("Meeting via AgenticAssistant", startTimeMillis, attendees);
    log("I", "CalendarService", "Created event at " + new Date(startTimeMillis).toLocaleString("en-US", { weekday: "short", hour: "numeric", minute: "2-digit" }) + ", Meet link " + result.meetLink);
    if (attendees.length) log("W", "CalendarService", "Invitations emailed to: " + attendees.join(", "));
    S.meetLink = result.meetLink;
    render();
    speak("Meeting scheduled successfully" + (attendees.length ? " with " + attendees.length + " participants" : "") + ".");
  }

  /* ---------- dailyBriefing ---------- */
  async function dailyBriefing() {
    if (!S.isSignedIn) { S.resultText = "Please sign in first."; render(); speak("Please sign in first."); return; }
    var run = S.run;
    S.isLoading = true; S.resultText = ""; render();
    log("D", "CalendarService", "getTodayEvents() orderBy=startTime singleEvents=true");
    await sleep(600);
    if (run !== S.run) return;
    var briefing = A.DailyBriefingGenerator.generate(new A.CalendarService(S.calendar, S.clock));
    S.resultText = "📊 Daily Briefing:\n\n" + briefing;
    S.isLoading = false; render();
    speak(briefing);
  }

  /* ---------- InboxMonitorWorker ---------- */
  var shade = document.getElementById("ph-shade"), shadeT;
  async function fastForward() {
    S.clock += 15 * A.MIN;
    var arr = D.ARRIVALS[S.arrivals % D.ARRIVALS.length];
    S.arrivals++;
    Mailbox.emails.unshift(Object.assign({ on: true }, arr, { id: arr.id + "-" + S.arrivals, date: new Date(S.clock).toDateString() }));
    Mailbox.changed();
    render();
    log("I", "WorkManager", "Running periodic work inbox_monitor_work (every 15 min, policy KEEP)");
    log("D", "InboxMonitorWorker", "InboxMonitorWorker started");
    if (!S.isSignedIn) { log("W", "InboxMonitorWorker", "User not signed in — skipping inbox check"); return; }
    var emails = await fetchRecentEmails(5);
    if (!emails.length) { log("D", "InboxMonitorWorker", "No new emails found"); return; }
    if (A.MeetingDetector.isMeetingRequired(emails)) {
      var count = A.MeetingDetector.detectMeetingEmails(emails).length;
      log("D", "InboxMonitorWorker", "Meeting request detected in " + count + " email(s)");
      notify("Meeting request detected", count + " email" + (count > 1 ? "s" : "") + " suggest a meeting. Open the app to review.");
    } else log("D", "InboxMonitorWorker", "No meeting-related emails found");
  }
  function notify(title, body) {
    log("I", "NotificationManager", "notify(1001) channel=inbox_monitor_channel \"" + title + "\"");
    shade.innerHTML = '<div class="notif" role="button" tabindex="0"><span class="ico">' + icon("i-bot") + "</span><div><small>Agentic Assistant · now</small><b>" + esc(title) + "</b><span>" + esc(body) + "</span></div></div>";
    shade.classList.add("show");
    clearTimeout(shadeT);
    shadeT = setTimeout(function () { shade.classList.remove("show"); }, 5200);
    shade.querySelector(".notif").addEventListener("click", function () { shade.classList.remove("show"); }); // setAutoCancel(true)
  }
  document.getElementById("ff-btn").addEventListener("click", fastForward);

  /* ---------- reset ---------- */
  document.getElementById("reset-btn").addEventListener("click", function () {
    var run = S.run + 1;
    S = freshState(); S.run = run;
    if ("speechSynthesis" in window) speechSynthesis.cancel();
    cap.classList.remove("show"); shade.classList.remove("show");
    Mailbox.reset();
    logEl.textContent = "";
    log("I", "MainActivity", "onCreate: isSignedIn = false");
    log("D", "MainActivity", "Inbox monitor worker scheduled");
    render();
  });

  /* ---------- control panel ---------- */
  var PHRASES = ["check my inbox", "schedule a meeting", "what's on my calendar", "daily briefing", "book a flight"];
  var chips = document.getElementById("phrase-chips");
  chips.innerHTML = PHRASES.map(function (p) { return '<button class="chip" type="button" data-say="' + esc(p) + '">“' + esc(p) + "”</button>"; }).join("");
  chips.addEventListener("click", function (e) { var b = e.target.closest("[data-say]"); if (b) { document.getElementById("say-input").value = b.dataset.say; requestVoiceInput(b.dataset.say); } });
  document.getElementById("say-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var v = document.getElementById("say-input").value.trim();
    if (v) requestVoiceInput(v);
  });
  var parseOut = document.getElementById("parse-out");
  function showParse(raw, parsed) {
    parseOut.innerHTML = parsed.command === "UNKNOWN"
      ? "No phrase matched, so <b>parseCommand()</b> returns <b>UNKNOWN</b>."
      : "Matched “" + esc(parsed.phrase) + "”, so <b>parseCommand()</b> returns <b>" + parsed.command + "</b>.";
  }
  var micBtn = document.getElementById("mic-btn"), micNote = document.getElementById("mic-note");
  if (!Recognition) {
    micToggle.disabled = true; micBtn.disabled = true;
    micNote.textContent = "This browser has no speech recognition, so type commands instead. Replies can still be read aloud.";
  }
  micBtn.addEventListener("click", function () {
    if (!micToggle.checked) { micToggle.checked = true; }
    requestVoiceInput();
  });
  if (!("speechSynthesis" in window)) { ttsToggle.disabled = true; }

  /* ---------- boot + one-time demo ---------- */
  log("I", "MainActivity", "onCreate: isSignedIn = false");
  log("D", "MainActivity", "Inbox monitor worker scheduled");
  render();
  var demoed = false;
  if ("IntersectionObserver" in window) {
    new IntersectionObserver(function (es, ob) {
      if (es[0].isIntersecting && !demoed) {
        demoed = true; ob.disconnect();
        setTimeout(function () { if (!S.isSignedIn) signIn(); setTimeout(function () { requestVoiceInput("check my inbox"); }, reduce ? 0 : 700); }, reduce ? 0 : 900);
      }
    }, { threshold: 0.5 }).observe(document.getElementById("screen"));
  }

  window.Phone = { state: function () { return S; }, run: requestVoiceInput, fastForward: fastForward, scanInbox: scanInbox, scheduleMeeting: scheduleMeeting, dailyBriefing: dailyBriefing, signIn: signIn };
})();
