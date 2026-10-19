# Play Store Submission — Accessibility Declaration (v1.1.0)

Whitelister uses `AccessibilityService`. It is NOT declared as an accessibility tool
(`isAccessibilityTool` is not set), so the app provides an in-app prominent disclosure
and affirmative consent (first-launch Consent screen) and must complete the Play Console
accessibility declaration.

## Required in-app disclosure (already implemented)
- First-launch Consent screen explaining AccessibilityService access, with an explicit
  "I Accept" button (affirmative consent). Declining exits the app.
- Always-reachable Info/policy screen (top-bar info icon) repeating the disclosure.
- Privacy Policy available in-app and at:
  https://raw.githubusercontent.com/mcap0/whitelister/main/PRIVACY.md

## Accessibility services declaration — answers to enter in Play Console
1. Why does your app need to use the Accessibility Services API?
   - Select: **App functionality**
2. Describe one core feature that requires the AccessibilityService API:
   - "Whitelister uses the AccessibilityService to read Instagram's on-screen UI for two
     user-controlled purposes: (1) detect when the user is viewing Reels and prevent
     scrolling between them, and (2) detect when the user has scrolled the Home feed
     below the Stories and bounce the feed back to the top. All processing is on-device;
     no data is collected."
3. Data collected via the API:
   - **None.** The view hierarchy/text is processed in memory on-device and is never
    stored, logged, or transmitted. The app has no INTERNET permission, no analytics,
    and no ads. Data Safety section: "No data collected."

## Notes / residual risk
- Google's 2026 policy tightening favors apps whose core purpose is serving a disability.
  Whitelister is a digital-wellbeing tool. The above disclosure + zero-data posture is the
  required and recommended compliance baseline; approval is not guaranteed.
- Pricing: $0.99. License: AGPL-3.0 + non-commercial (derivatives must stay open and
  non-commercial; only the copyright holder may sell).
## Production access application (after the 14-day closed test)

Draft answers for "Apply for production" in Play Console. Text in [brackets] must be
filled in / checked with real numbers before submitting. Do not invent tester data.

**1. How easy was it to recruit testers for your app?**
[Very easy / Easy / Neither easy nor difficult / Difficult / Very difficult]

**2. Describe the engagement you received from testers during your closed test.**
> [N] testers joined the closed test, mostly friends, colleagues and people who, like me,
> wanted to spend less time on Instagram. Most of them kept Remove Reels and Block Feed
> enabled every day and used Instagram normally on top of it: opening Reels sent by
> friends, chatting, searching profiles. This was exactly the real-world usage I needed,
> because the hard part of the app is blocking the endless scroll without breaking
> everything else Instagram is used for.

**3. Provide a summary of the feedback you received from testers. Include how you collected it.**
> I collected feedback directly from testers via chat (WhatsApp/Telegram) and in person,
> and by reproducing every report on my own device with debug builds. The main points were:
> a Reel sometimes closed right after being reopened; blocking the Home feed reloaded the
> feed, which felt annoying; a fast scroll could sometimes slip past the block; testers
> asked for a way to block the Explore grid too; and some asked for a softer mode instead
> of an all-or-nothing block. [add/remove points based on what testers actually said]

**4. Who is the intended audience of your app?**
> Adults who use Instagram and want to keep using it for what matters to them (messages,
> friends, the accounts they care about) without being pulled into endless Reels and
> suggested content. People who struggle with compulsive scrolling and want a simple,
> private digital-wellbeing tool.

**5. Describe how your app provides value to users.**
> Whitelister removes the endless-scroll mechanics from Instagram without making it
> unusable. You can still watch the Reel you opened, but not swipe to the next one; the
> Home feed stays at the Stories; Explore suggestions are blocked while search keeps
> working. A daily free-time option lets users decide how many minutes per day they allow
> themselves. Everything runs on-device, with no internet permission, no data collection,
> no ads and no account.

**6. How many installs do you expect your app to have in your first year?**
[pick a range — paid app at $0.99, niche audience: e.g. 0–10K]

**7. What changes did you make to your app based on what you learned during your closed test?**
> Version 1.4.0 → 1.4.2 were driven by tester feedback: fixed a Reel closing right after
> being reopened; Block Feed now scrolls the feed back without reloading it; the snap-back
> became faster than the user's scroll, and if the user insists it falls back to tapping
> Home/Search; added Block Explore; added a per-feature daily free time (5–60 minutes,
> resets at midnight); and a cleaner interface with usage progress shown for each feature.

**8. How did you decide that your app is ready for production?**
> The core features worked reliably for testers in daily use across every Reel surface
> (Reels tab, feed, Direct messages, profiles, chats), the last builds received no new
> blocking-related bug reports [confirm], and the app has no crashes reported in Play
> Console vitals [confirm]. Privacy requirements are met: no data is collected, there is
> no internet permission, and the Accessibility disclosure and consent are shown in-app.
