# Play Store Submission Kit (v1.1.1)

Whitelister uses `AccessibilityService`. It is NOT declared as an accessibility tool
(`isAccessibilityTool` is not set), so the app provides an in-app prominent disclosure
and affirmative consent (first-launch Consent screen) and must complete the Play Console
accessibility declaration.

## State
- **main = v1.1.1 / versionCode 31** (closed-test hotfix: cold-start fix + legacy appicon).
- AAB to upload: `~/Scaricati/whitelist-v1.1.1.aab` (signed, legacy `appicon` as launcher icon).
  Verified icon resource: `base/res/drawable-nodpi-v4/appicon.jpg`.
- GitHub: release `v1.1.1` (stable) already re-cut with the corrected APK.

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

To open the declaration: `https://play.google.com/console/u/0/developers/{ID}/app/com.whitelister.app/accessibility-declaration`
or search "accessibility" in the Console search bar, or Configurazione → Altre opzioni.

## Store listing kit
- **App name**: Whitelister
- **Category**: Productivity
- **Short description** (≤80 chars):
  `Blocks Instagram Reels scrolling and locks your Home feed at the top.`
- **Full description**:
  ```
  Whitelister helps you reclaim control over social media. It is built function by
  function and works entirely on-device — no account, no data, no tracking.

  What it does today:
  • Remove Reels — blocks the endless scroll between Instagram Reels. You can still
    open a single reel, but you cannot swipe into the next one.
  • Lock Home Feed — keeps you at the top of your For You feed (Stories only). Your
    Favorites feed and Reels stay exactly as they are.

  How it works
  Whitelister uses Android's AccessibilityService to read the Instagram screen in real
  time and apply your choices automatically. Everything is processed in memory on your
  phone. Whitelister has no Internet permission, collects nothing, and shows no ads.

  • Works on Android 11 and later
  • No root required
  • One-tap toggles
  • Privacy by design: nothing ever leaves your device

  Note: the first time you enable the service you must turn it on from
  Android Settings → Accessibility → Whitelister. The app guides you through this once.
  ```
- **App icon**: upload `~/Scaricati/appicon.jpg` (512×512) in the store listing.
- **Feature graphic**: 1024×500, optional for now (can add later).
- **Screenshots**: 2 required before production. The two photos already in `~/Scaricati`
  (`photo_...727_x.jpg` 783×800 and `watermark-removed-photo...` 1080×522) can be uploaded
  if they show the two features; otherwise capture fresh screenshots on the Pixel.
- **Data safety**: no data collected / no data shared (all toggles stored on-device only).
- **Content rating questionnaire**: no violence, no sexual/inappropriate content, no
  drugs, no gambling, no user-messaging; not designed for children. Ends at the lowest
  applicable rating.
- **Pricing**: $0.99 — set at the production stage (testing tracks are always free).

## Closed-test publishing steps (unchanged)
1. Console → Test chiusi → **Nuova release** → upload `whitelist-v1.1.1.aab`.
2. Add testers by email; share the opt-in link; each tester must open it and install
   from Play.
3. Keep ≥12 active testers for ≥14 days, then "Richiedi accesso produzione".

## Notes / residual risk
- Google's 2026 policy tightening favors apps whose core purpose is serving a disability.
  Whitelister is a digital-wellbeing tool. The above disclosure + zero-data posture is the
  required and recommended compliance baseline; approval is not guaranteed.
- Pricing: $0.99. License: AGPL-3.0 + non-commercial (derivatives must stay open and
  non-commercial; only the copyright holder may sell).