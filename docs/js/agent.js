/* A line-by-line port of the app's local logic (agent/, voice/VoiceCommandManager.parseCommand,
   CalendarService helpers and MainActivity.resolveStartTime) running against a simulated clock
   and a made-up Gmail inbox and Google Calendar. Nothing here talks to Google. */
(function (root) {
  "use strict";

  /* ---------- model ---------- */
  // EmailMessage(id, subject, snippet, from, date)

  /* ---------- agent/EmailAnalyzer.kt ---------- */
  var EmailAnalyzer = {
    summarize: function (emails) {
      return emails.map(function (email) {
        var subject = email.subject.trim() === "" ? "(no subject)" : email.subject;
        var snippet = email.snippet.slice(0, 120);
        if (email.snippet.length > 120) snippet += "…";
        return subject + " — " + snippet;
      });
    },
  };

  /* ---------- agent/MeetingDetector.kt ---------- */
  var MEETING_KEYWORDS = ["meeting", "discuss", "call", "sync", "schedule", "agenda", "catch up", "standup", "stand-up", "huddle", "conference"];
  function meetingText(email) { return (email.subject + " " + email.snippet).toLowerCase(); }
  var MeetingDetector = {
    KEYWORDS: MEETING_KEYWORDS,
    isMeetingRequired: function (emails) {
      return emails.some(function (e) { var t = meetingText(e); return MEETING_KEYWORDS.some(function (k) { return t.indexOf(k) >= 0; }); });
    },
    detectMeetingEmails: function (emails) {
      return emails.filter(function (e) { var t = meetingText(e); return MEETING_KEYWORDS.some(function (k) { return t.indexOf(k) >= 0; }); });
    },
    // which keywords matched (for highlighting)
    matchedKeywords: function (email) {
      var t = meetingText(email);
      return MEETING_KEYWORDS.filter(function (k) { return t.indexOf(k) >= 0; });
    },
  };

  /* ---------- agent/TimeExtractor.kt ---------- */
  var TIME_SRC = "\\b(\\d{1,2}(:\\d{2})?\\s*(am|pm|AM|PM|a\\.m\\.|p\\.m\\.))\\b";
  var TOMORROW_SRC = "\\btomorrow\\s+at\\s+(\\d{1,2}(:\\d{2})?(\\s*(am|pm|AM|PM))?)\\b";
  var DAY_SRC = "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+(at\\s+)?(\\d{1,2}(:\\d{2})?\\s*(am|pm|AM|PM)?)\\b";
  function all(src, flags, text) { var re = new RegExp(src, flags + "g"), out = [], m; while ((m = re.exec(text))) { out.push(m); if (m[0] === "") re.lastIndex++; } return out; }
  function endsWithIgnoreCase(s, suffix) { return s.toLowerCase().slice(-suffix.length) === suffix.toLowerCase(); }
  var TimeExtractor = {
    PATTERNS: { TIME: TIME_SRC, TOMORROW: TOMORROW_SRC, DAY_TIME: DAY_SRC },
    extractTimes: function (text) {
      var results = new Set(); // Kotlin's mutableSetOf keeps insertion order, like Set
      all(TOMORROW_SRC, "i", text).forEach(function (m) { results.add("Tomorrow at " + m[1].trim()); });
      all(DAY_SRC, "i", text).forEach(function (m) {
        var day = m[1].charAt(0).toUpperCase() + m[1].slice(1);
        results.add(day + " " + m[3].trim());
      });
      all(TIME_SRC, "", text).forEach(function (m) {
        var time = m[1].trim();
        var dup = Array.from(results).some(function (r) { return endsWithIgnoreCase(r, time); });
        if (!dup) results.add(time);
      });
      return Array.from(results);
    },
    extractTimesFromEmails: function (emails) {
      var allTimes = new Set();
      emails.forEach(function (e) { TimeExtractor.extractTimes(e.subject + " " + e.snippet).forEach(function (t) { allTimes.add(t); }); });
      return Array.from(allTimes);
    },
    // spans for highlighting in the editor
    spans: function (text) {
      var s = [];
      [["TOMORROW", TOMORROW_SRC, "i"], ["DAY_TIME", DAY_SRC, "i"], ["TIME", TIME_SRC, ""]].forEach(function (p) {
        all(p[1], p[2], text).forEach(function (m) { s.push({ start: m.index, end: m.index + m[0].length, kind: "time" }); });
      });
      return s;
    },
  };

  /* ---------- agent/ParticipantExtractor.kt ---------- */
  var EMAIL_SRC = "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}";
  function distinct(list) { return Array.from(new Set(list)); }
  var ParticipantExtractor = {
    REGEX: EMAIL_SRC,
    extractFromEmail: function (email) {
      var combined = email.from + " " + email.subject + " " + email.snippet;
      return distinct(all(EMAIL_SRC, "", combined).map(function (m) { return m[0].toLowerCase(); }));
    },
    extractFromEmails: function (emails) {
      return distinct([].concat.apply([], emails.map(ParticipantExtractor.extractFromEmail)));
    },
  };

  /* ---------- voice/VoiceCommandManager.parseCommand ---------- */
  var COMMAND_PHRASES = {
    SCAN_INBOX: ["scan inbox", "check inbox", "check my inbox", "scan my inbox", "check email", "check my email"],
    SCHEDULE_MEETING: ["schedule meeting", "schedule a meeting", "create meeting", "create a meeting", "book meeting", "book a meeting", "set up a meeting", "set up meeting"],
    DAILY_BRIEFING: ["daily briefing", "today briefing", "what meetings", "meetings today", "my meetings", "today's meetings", "what's on my calendar", "what do i have today"],
  };
  function parseCommand(text) {
    var lower = text.toLowerCase().trim();
    var order = ["SCAN_INBOX", "SCHEDULE_MEETING", "DAILY_BRIEFING"];
    for (var i = 0; i < order.length; i++) {
      var hit = COMMAND_PHRASES[order[i]].find(function (p) { return lower.indexOf(p) >= 0; });
      if (hit) return { command: order[i], phrase: hit };
    }
    return { command: "UNKNOWN", phrase: null };
  }

  /* ---------- time helpers: MainActivity.resolveStartTime / applyTimeToCal ---------- */
  var HOUR = 3600000, MIN = 60000;
  function applyTimeToCal(d, timePart) {
    var cleaned = timePart.replace(/\./g, "").trim();
    var isPm = /pm/i.test(cleaned), isAm = /am/i.test(cleaned);
    var numeric = cleaned.replace(/[^0-9:]/g, "").trim();
    var parts = numeric.split(":");
    var hour = parseInt(parts[0], 10); if (isNaN(hour)) hour = 9;
    var minute = parseInt(parts[1], 10); if (isNaN(minute)) minute = 0;
    if (isPm && hour < 12) hour += 12;
    if (isAm && hour === 12) hour = 0;
    d.setHours(hour, minute, 0, 0);
  }
  function removePrefix(s, p) { return s.indexOf(p) === 0 ? s.slice(p.length) : s; }
  var DAYS = [["monday", 1], ["tuesday", 2], ["wednesday", 3], ["thursday", 4], ["friday", 5], ["saturday", 6], ["sunday", 0]];
  function resolveStartTime(slot, nowMs) {
    if (slot == null) return nowMs + HOUR;
    var d = new Date(nowMs);
    var lower = slot.toLowerCase().trim();
    if (lower.indexOf("tomorrow") === 0) {
      d.setDate(d.getDate() + 1);
      // removePrefix("tomorrow").removePrefix("at") leaves " at 4" untouched because of the leading space
      applyTimeToCal(d, removePrefix(removePrefix(lower, "tomorrow"), "at").trim());
      return d.getTime();
    }
    for (var i = 0; i < DAYS.length; i++) {
      if (lower.indexOf(DAYS[i][0]) === 0) {
        var timePart = removePrefix(lower, DAYS[i][0]).trim();
        // Calendar.DAY_OF_WEEK is Sunday=1..Saturday=7; differences are the same as JS getDay()
        var diff = DAYS[i][1] - d.getDay();
        if (diff <= 0) diff += 7;
        d.setDate(d.getDate() + diff);
        applyTimeToCal(d, timePart);
        return d.getTime();
      }
    }
    applyTimeToCal(d, lower);
    if (d.getTime() < nowMs) d.setDate(d.getDate() + 1);
    return d.getTime();
  }

  /* ---------- service/CalendarService.kt (against a fake calendar) ---------- */
  function fmtClock(ms) {
    var d = new Date(ms), h = d.getHours(), m = d.getMinutes();
    var ampm = h < 12 ? "AM" : "PM", dh = h === 0 ? 12 : h > 12 ? h - 12 : h;
    return dh + ":" + String(m).padStart(2, "0") + " " + ampm;
  }
  function sameDay(a, b) { var x = new Date(a), y = new Date(b); return x.getFullYear() === y.getFullYear() && x.getMonth() === y.getMonth() && x.getDate() === y.getDate(); }
  function CalendarService(events, nowMs) { this.events = events; this.now = nowMs; }
  CalendarService.prototype.getTodayEvents = function () {
    var now = this.now;
    return this.events.filter(function (e) { return sameDay(e.start, now); })
      .sort(function (a, b) { return a.start - b.start; })
      .map(function (e) { return (e.title || "(No title)") + " at " + (e.allDay ? "All day" : fmtClock(e.start)); });
  };
  CalendarService.prototype.formatTimeSlot = function (ms) {
    // "Tomorrow" is used for any day that isn't today, even three days out
    return sameDay(ms, this.now) ? fmtClock(ms) : "Tomorrow " + fmtClock(ms);
  };
  CalendarService.prototype.getFreeSlots = function (requested, durationMinutes) {
    durationMinutes = durationMinutes || 30;
    var winStart = requested - 2 * HOUR, winEnd = requested + 24 * HOUR;
    var busy = this.events.filter(function (e) { return !e.allDay && e.end > winStart && e.start < winEnd; })
      .map(function (e) { return [e.start, e.end]; });
    var dur = durationMinutes * MIN, slots = [], c = requested, limit = requested + 24 * HOUR;
    while (c < limit && slots.length < 5) {
      var ce = c + dur;
      var free = !busy.some(function (b) { return c < b[1] && ce > b[0]; });
      if (free) slots.push(this.formatTimeSlot(c));
      c += 30 * MIN;
    }
    return slots;
  };
  CalendarService.prototype.createMeetingEvent = function (title, startMs, attendees, rand) {
    var ev = { title: title, start: startMs, end: startMs + 30 * MIN, attendees: attendees || [], created: true };
    this.events.push(ev);
    var r = rand || Math.random, letters = "abcdefghijkmnopqrstuvwxyz";
    function pick(n) { var s = ""; for (var i = 0; i < n; i++) s += letters[Math.floor(r() * letters.length)]; return s; }
    return { meetLink: "https://meet.google.com/" + pick(3) + "-" + pick(4) + "-" + pick(3), eventId: "evt_" + pick(8), scheduledTime: new Date(startMs).toString(), event: ev };
  };

  /* ---------- agent/DailyBriefingGenerator.kt ---------- */
  var DailyBriefingGenerator = {
    generate: function (calendarService) {
      var events = calendarService.getTodayEvents();
      if (!events.length) return "You have no meetings scheduled for today. Your calendar is clear.";
      var s = "Today you have " + events.length + " meeting" + (events.length > 1 ? "s" : "") + ". ";
      events.forEach(function (e, i) { s += (i + 1) + ". " + e + ". "; });
      return s.trim();
    },
  };

  root.Agent = {
    EmailAnalyzer: EmailAnalyzer, MeetingDetector: MeetingDetector, TimeExtractor: TimeExtractor,
    ParticipantExtractor: ParticipantExtractor, parseCommand: parseCommand, COMMAND_PHRASES: COMMAND_PHRASES,
    resolveStartTime: resolveStartTime, applyTimeToCal: applyTimeToCal, CalendarService: CalendarService,
    DailyBriefingGenerator: DailyBriefingGenerator, fmtClock: fmtClock, sameDay: sameDay, HOUR: HOUR, MIN: MIN,
  };
})(typeof window !== "undefined" ? window : globalThis);
