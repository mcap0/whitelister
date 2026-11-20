# Play Store Submission — Accessibility Declaration (v1.6.0)

Whitelister uses `AccessibilityService`. It is NOT declared as an accessibility tool
(`isAccessibilityTool` is not set), so Google Play requires: an in-app prominent disclosure with
affirmative consent, a privacy policy both in Play Console and inside the app, and the Play
Console accessibility declaration (with a video). Policy pages:
https://support.google.com/googleplay/android-developer/answer/10964491 (AccessibilityService API)
and https://support.google.com/googleplay/android-developer/answer/10144311 (User Data).

## In-app disclosure, consent and privacy policy (implemented, v1.5.0)
- First-launch disclosure screen ("Before you start"): what the service reads (Instagram and
  Facebook screen content: layout, view ids, visible text such as account names and captions;
  no other app), why (Remove Reels on Instagram and Facebook, Block Feed, Block Explore, usage
  minutes for the free time), what happens to it (processed in memory on-device, never
  stored or sent; only settings, the free minutes used and when the current block started are
  saved locally), how to revoke it.
- Affirmative consent: "I agree" / "Decline". Decline closes the app; Back/Home are not consent;
  the screen comes back on every launch until accepted. Consent is versioned
  (`PreferencesManager.CONSENT_VERSION`, currently 3 = Facebook Home feed, v1.6.1): bump it whenever what the
  service reads changes, so everyone is asked again.
- The accessibility service reads nothing before consent (`onAccessibilityEvent` returns early),
  even if it was switched on from Android Settings first, and its runtime scope is limited to
  `com.instagram.android` + `com.facebook.katana` (`setServiceInfo` packageNames).
- Privacy Policy inside the app: "Read the Privacy Policy" on the disclosure, the info icon on the
  main screen and the "Privacy policy & permissions" footer link (text in `strings.xml`, same as
  `PRIVACY.md`). Public URL for Play Console:
  https://github.com/mcap0/whitelister/blob/main/PRIVACY.md (or the raw URL
  https://raw.githubusercontent.com/mcap0/whitelister/main/PRIVACY.md).
- History: the disclosure screen added in v1.0.5 was accidentally deleted by commit dd7f245
  ("fix: block first scroll in Reels tab"), so v1.3.0 – v1.4.3 shipped WITHOUT it. Restored in
  v1.5.0 — keep it when rewriting `MainActivity`.

## Accessibility services declaration — answers to enter in Play Console
Resubmit the declaration with every release that changes how the API is used (v1.5.0 added
Facebook Remove Reels, v1.6.0 Facebook Block Feed) — "If you change how your app uses this API, you must submit the form again".
1. Why does your app need to use the Accessibility Services API?
   - Select: **App functionality**
2. Does your app collect or share personal or sensitive data through accessibility capabilities?
   - **No.** The screen content is processed in memory on-device and is never stored, logged or
     transmitted. The app has no INTERNET permission, no analytics and no ads. Data safety
     section: "No data collected", "No data shared".
3. Describe the core feature that requires the AccessibilityService API:
   - "Whitelister is a digital-wellbeing tool. At the user's request it reads the on-screen UI of
     Instagram and Facebook (only those two apps) to recognise endless-scroll surfaces and apply
     fixed, user-selected rules: Remove Reels (Instagram and Facebook) — when the user swipes from
     the Reel they opened to another one, the app performs a Back action; Block Feed (Instagram and
     Facebook) and Block Explore (Instagram) — when the Home feed or Explore grid is scrolled, the app scrolls it back
     to the top (or taps the Home/Search tab). An optional free time lets the user allow N
     minutes before a block starts; the block then lasts 1–24 hours (user-selected). All processing is on-device; no data is collected."
   - These are deterministic, rule-based actions ("if the user swipes to another Reel, go back"),
     which the policy explicitly allows; nothing is decided autonomously.
4. Video (required): a link (e.g. unlisted YouTube) showing, in order —
   1. the app being opened on a device (fresh install or cleared storage);
   2. the disclosure screen with its full text visible (scroll slowly);
   3. the consent path: "I agree", then "Enable", then switching Whitelister on in Android
      Settings > Accessibility;
   4. the non-consent path: "Decline" closes the app, reopening it shows the disclosure again;
   5. a core feature: e.g. open a Reel in Instagram or Facebook, swipe to the next one, Back is
      performed (add captions, the UI alone does not explain it).

Declaration video for v1.6.0 (recorded 19 Nov 2026 on an emulator with a test Instagram
account, captioned, 1080x1920, 88 s): `~/Videos/whitelister/whitelister_declaration_v1.6.0.mp4`
(not in git), public on YouTube: https://youtube.com/shorts/bEAZM6H-rjY (paste this link in the
declaration). Covers steps 1–5 above: disclosure, Decline closes the app, reopen shows it again,
I agree, Enable → Accessibility → allow, then Block Feed and Remove Reels on Instagram.

## Notes / residual risk
- Google's 2026 policy tightening favors apps whose core purpose is serving a disability.
  Whitelister is a digital-wellbeing tool. The above disclosure + zero-data posture is the
  required and recommended compliance baseline; approval is not guaranteed.
- Store listing: Instagram and Facebook are named only descriptively ("works on"), never in the
  title or icon, with the non-affiliation disclaimer (Instagram, Facebook, Meta) at the end.
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
> Adults who use Instagram or Facebook and want to keep using them for what matters to them (messages,
> friends, the accounts they care about) without being pulled into endless Reels and
> suggested content. People who struggle with compulsive scrolling and want a simple,
> private digital-wellbeing tool.

**5. Describe how your app provides value to users.**
> Whitelister removes the endless-scroll mechanics from Instagram and Facebook without making
> them unusable. You can still watch the Reel you opened, but not swipe to the next one; the
> Home feed stays at the Stories; Explore suggestions are blocked while search keeps
> working. A free-time option lets users decide how many minutes they allow themselves
> and how long the block lasts afterwards (1–24 hours). Everything runs on-device, with no internet permission, no data collection,
> no ads and no account.

**6. How many installs do you expect your app to have in your first year?**
[pick a range — paid app at $0.99, niche audience: e.g. 0–10K]

**7. What changes did you make to your app based on what you learned during your closed test?**
> Version 1.4.0 → 1.4.2 were driven by tester feedback: fixed a Reel closing right after
> being reopened; Block Feed now scrolls the feed back without reloading it; the snap-back
> became faster than the user's scroll, and if the user insists it falls back to tapping
> Home/Search; added Block Explore; added a per-feature daily free time (5–60 minutes,
> resets at midnight); and a cleaner interface with usage progress shown for each feature.
> Version 1.5.0 extended Remove Reels to Facebook and 1.6.0 Block Feed [say whether testers asked for it — confirm]
> and restored the first-launch accessibility disclosure and in-app privacy policy.

**8. How did you decide that your app is ready for production?**
> The core features worked reliably for testers in daily use across every Reel surface
> (Reels tab, feed, Direct messages, profiles, chats), the last builds received no new
> blocking-related bug reports [confirm], and the app has no crashes reported in Play
> Console vitals [confirm]. Privacy requirements are met: no data is collected, there is
> no internet permission, and the Accessibility disclosure and consent are shown in-app.
