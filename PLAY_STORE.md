# Play Store Submission — Accessibility Declaration (v1.0.5)

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
   - "Whitelister uses the AccessibilityService to read Instagram's on-screen UI to detect
    when the user is viewing Reels (and prevent scrolling between them), and to detect and
    hide sponsored, recommended, and reel posts from the feed by tapping Instagram's own
    'Hide ad' / 'Not interested' option. All processing is on-device; no data is collected."
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
