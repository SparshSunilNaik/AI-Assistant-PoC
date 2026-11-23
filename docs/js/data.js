/* Made-up mailbox and calendar for the simulator. Every name and address is invented (.example domains). */
(function (root) {
  "use strict";
  // The simulated phone clock starts on Monday 9 March 2026 at 9:00 AM, the week the app was built.
  var START = new Date(2026, 2, 9, 9, 0, 0, 0).getTime();

  var INBOX = [
    { id: "m1", from: "Priya Raman <priya@northwind.example>", date: "Mon, 9 Mar 2026 08:52", subject: "Sync on the Q2 roadmap",
      snippet: "Hi! Can we sync tomorrow at 4 to go over the roadmap? I'm looping in sam@northwind.example so he can walk us through the numbers." },
    { id: "m2", from: "The Weekly Byte <news@weeklybyte.example>", date: "Mon, 9 Mar 2026 08:30", subject: "This week: tiny models, big ideas",
      snippet: "Five stories worth your coffee: on-device speech gets faster, a new open dataset, and why your phone is now a decent server." },
    { id: "m3", from: "Billing <billing@acme-supplies.example>", date: "Mon, 9 Mar 2026 08:05", subject: "Invoice #4821",
      snippet: "A friendly reminder: please recall that invoice #4821 for office supplies is due on Friday. You can pay online at any time." },
    { id: "m4", from: "Marco Bellini <marco@studio.example>", date: "Sun, 8 Mar 2026 19:40", subject: "Design review",
      snippet: "Agenda attached for the onboarding redesign. Thursday 2:30 pm works for me, does it work for you?" },
    { id: "m5", from: "Parcel Point <orders@parcelpoint.example>", date: "Sun, 8 Mar 2026 16:12", subject: "Your package has shipped",
      snippet: "Good news! Order 77-1204 is on its way and should arrive by Wednesday. Track it any time from your account." },
    { id: "m6", from: "Dana Okafor <dana@northwind.example>", date: "Sun, 8 Mar 2026 13:03", subject: "Lunch?",
      snippet: "Free for lunch on Wednesday at 12:30 pm? There's a new ramen place near the office I've been meaning to try." },
    { id: "m7", from: "Deploy Bot <devops@northwind.example>", date: "Sat, 7 Mar 2026 22:47", subject: "Nightly deploy finished",
      snippet: "All services are green. Async jobs are back to normal and the queue drained in 4 minutes. No action needed." },
    { id: "m8", from: "Hiring Team <hr@northwind.example>", date: "Sat, 7 Mar 2026 11:20", subject: "Re: interview panel",
      snippet: "Could we schedule the panel for Monday 10 am? Two candidates are confirmed and we just need three interviewers." },
    { id: "m9", from: "Mum <mum@family.example>", date: "Fri, 6 Mar 2026 21:15", subject: "Photos from the weekend",
      snippet: "Here are the photos from Saturday! The one of the dog in the sunglasses is my favourite. Call me when you're free." },
    { id: "m10", from: "Account Security <no-reply@accounts.example>", date: "Fri, 6 Mar 2026 09:58", subject: "New sign-in on Android",
      snippet: "We noticed a new sign-in to your account from an Android device. If this was you, there's nothing else you need to do." },
  ];

  function at(dayOffset, h, m, durMin, title) {
    var d = new Date(START); d.setDate(d.getDate() + dayOffset); d.setHours(h, m, 0, 0);
    return { title: title, start: d.getTime(), end: d.getTime() + durMin * 60000 };
  }
  var CALENDAR = [
    at(0, 9, 30, 15, "Team standup"),
    at(0, 13, 0, 60, "Design crit"),
    at(0, 16, 0, 30, "1:1 with Priya"),
    at(1, 10, 0, 60, "Quarterly planning"),
    at(1, 16, 0, 60, "Dentist"),
  ];

  // New emails that "arrive" each time the background worker is fast-forwarded
  var ARRIVALS = [
    { id: "n1", from: "Sam Ortiz <sam@northwind.example>", date: "Mon, 9 Mar 2026", subject: "Quick huddle?", snippet: "Got 10 minutes for a quick huddle about the launch checklist? Anytime after lunch works." },
    { id: "n2", from: "Gym <hello@flexgym.example>", date: "Mon, 9 Mar 2026", subject: "Your membership renews soon", snippet: "Your plan renews on the 15th. Nothing to do if you'd like to keep training with us." },
    { id: "n3", from: "Lena Fischer <lena@studio.example>", date: "Mon, 9 Mar 2026", subject: "Conference talk", snippet: "Are you still up for co-presenting at the design conference? Let me know by Friday 5 pm." },
  ];

  root.SimData = { START: START, INBOX: INBOX, CALENDAR: CALENDAR, ARRIVALS: ARRIVALS, ACCOUNT: "you@example.com" };
})(typeof window !== "undefined" ? window : globalThis);
