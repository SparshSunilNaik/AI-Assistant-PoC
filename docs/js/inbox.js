/* Editable inbox with live highlights from MeetingDetector, TimeExtractor and ParticipantExtractor. */
(function () {
  "use strict";
  var A = window.Agent, M = window.Mailbox;
  var mailsEl = document.getElementById("mails"), side = document.getElementById("analysis");
  function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }

  // Build non-overlapping highlight spans: keywords (substring, case-insensitive), times, email addresses
  function spans(text) {
    var out = [], lower = text.toLowerCase();
    A.MeetingDetector.KEYWORDS.forEach(function (k) {
      var i = lower.indexOf(k);
      while (i >= 0) { out.push({ start: i, end: i + k.length, cls: "kw", title: "meeting keyword “" + k + "”" }); i = lower.indexOf(k, i + 1); }
    });
    A.TimeExtractor.spans(text).forEach(function (s) { out.push({ start: s.start, end: s.end, cls: "tm", title: "time reference" }); });
    var re = new RegExp(A.ParticipantExtractor.REGEX, "g"), m;
    while ((m = re.exec(text))) out.push({ start: m.index, end: m.index + m[0].length, cls: "em", title: "email address" });
    // longest first, drop overlaps
    out.sort(function (a, b) { return a.start - b.start || (b.end - b.start) - (a.end - a.start); });
    var kept = [], lastEnd = -1;
    out.forEach(function (s) { if (s.start >= lastEnd) { kept.push(s); lastEnd = s.end; } });
    return kept;
  }
  function highlight(text) {
    var h = "", pos = 0;
    spans(text).forEach(function (s) {
      h += esc(text.slice(pos, s.start)) + '<mark class="' + s.cls + '" title="' + s.title + '">' + esc(text.slice(s.start, s.end)) + "</mark>";
      pos = s.end;
    });
    return h + esc(text.slice(pos));
  }

  function verdict(e) {
    var kws = A.MeetingDetector.matchedKeywords(e);
    var times = A.TimeExtractor.extractTimes(e.subject + " " + e.snippet);
    var p = [];
    if (kws.length) {
      var within = kws.filter(function (k) { return !new RegExp("\\b" + k.replace(/[-]/g, "\\-") + "\\b", "i").test(e.subject + " " + e.snippet); });
      p.push('<span class="pill yes">Meeting request</span>');
      if (within.length) p.push('<span class="pill warn">“' + within.join("”, “") + "” only matches inside another word</span>");
    } else p.push('<span class="pill">Not a meeting</span>');
    if (times.length) p.push('<span class="pill">' + times.length + " time" + (times.length > 1 ? "s" : "") + ": " + esc(times.join(", ")) + "</span>");
    if (!kws.length && times.length) p.push('<span class="pill warn">Has a time but no keyword, so it’s ignored</span>');
    return p.join("");
  }

  function renderMails() {
    mailsEl.innerHTML = M.emails.map(function (e, i) {
      return '<article class="card mail' + (e.on ? "" : " off") + '" data-i="' + i + '">' +
        '<input type="checkbox" ' + (e.on ? "checked" : "") + ' aria-label="Include this email in the inbox">' +
        '<div><div class="from"><span>' + esc(e.from) + "</span><span>" + esc(e.date) + "</span></div>" +
        '<div class="subj" contenteditable="true" spellcheck="false" data-f="subject" aria-label="Subject">' + highlight(e.subject) + "</div>" +
        '<div class="snip" contenteditable="true" spellcheck="false" data-f="snippet" aria-label="Email text">' + highlight(e.snippet) + "</div>" +
        '<div class="verdict-row">' + verdict(e) + "</div></div></article>";
    }).join("");
  }
  function renderSide() {
    var active = M.active();
    var top10 = active.slice(0, 10);
    var meet = A.MeetingDetector.detectMeetingEmails(top10);
    var slots = A.TimeExtractor.extractTimesFromEmails(meet);
    var people = A.ParticipantExtractor.extractFromEmails(meet);
    var first = slots[0];
    var when = first ? new Date(A.resolveStartTime(first, window.Phone.state().clock)) : null;
    side.innerHTML = "<h3>What the phone will find</h3>" +
      '<div class="legend"><mark class="kw" style="background:var(--kw);color:var(--kw-ink)">keyword</mark><mark style="background:var(--tm);color:var(--tm-ink)">time</mark><mark style="background:var(--em);color:var(--em-ink)">address</mark></div>' +
      '<div><span class="label">Scan reads the newest 10</span><p style="margin:4px 0 0">' + meet.length + " of " + top10.length + " look like meeting requests.</p></div>" +
      '<div><span class="label">Proposed slots</span>' + (slots.length ? "<ul>" + slots.map(function (s) { return "<li>" + esc(s) + "</li>"; }).join("") + "</ul>" : '<p class="muted" style="margin:4px 0 0">None found</p>') + "</div>" +
      (first ? '<div><span class="label">Schedule would book</span><p style="margin:4px 0 0">“' + esc(first) + "” → " + esc(when.toLocaleString("en-US", { weekday: "long", hour: "numeric", minute: "2-digit" })) + "</p></div>" : "") +
      '<div><span class="label">Would invite</span>' + (people.length ? "<ul>" + people.map(function (s) { return "<li>" + esc(s) + "</li>"; }).join("") + "</ul>" : '<p class="muted" style="margin:4px 0 0">Nobody</p>') + "</div>" +
      '<div style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn small primary" type="button" id="inbox-try">Scan on the phone</button><button class="btn small" type="button" id="inbox-reset">Reset inbox</button></div>';
    side.querySelector("#inbox-try").addEventListener("click", function () {
      document.getElementById("simulator").scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
      window.Phone.run("check my inbox");
    });
    side.querySelector("#inbox-reset").addEventListener("click", function () { M.reset(); });
  }

  mailsEl.addEventListener("change", function (e) {
    if (e.target.type !== "checkbox") return;
    var i = Number(e.target.closest(".mail").dataset.i);
    M.emails[i].on = e.target.checked;
    M.changed();
  });
  // Re-highlight when the editor loses focus, so the caret isn't disturbed while typing
  mailsEl.addEventListener("input", function (e) {
    var f = e.target.dataset && e.target.dataset.f;
    if (!f) return;
    var i = Number(e.target.closest(".mail").dataset.i);
    M.emails[i][f] = e.target.textContent;
    var row = e.target.closest(".mail").querySelector(".verdict-row");
    row.innerHTML = verdict(M.emails[i]);
    renderSide();
  });
  mailsEl.addEventListener("focusout", function (e) {
    if (e.target.dataset && e.target.dataset.f) e.target.innerHTML = highlight(e.target.textContent);
  });
  mailsEl.addEventListener("keydown", function (e) { if (e.key === "Enter" && e.target.dataset && e.target.dataset.f) { e.preventDefault(); e.target.blur(); } });

  M.listeners.push(function () { renderMails(); renderSide(); });
  renderMails(); renderSide();
})();
