# Whitelister Privacy Policy

Last updated: 20 November 2026

Whitelister is an open-source Android app that helps you spend less time on Instagram and
Facebook by blocking the endless-scroll features you choose to block. This policy explains what
data the app accesses and what it does with it. The same policy is available inside the app
(info icon on the main screen, or "Read the Privacy Policy" on the first-launch disclosure).

## What data the app accesses
Whitelister uses Android's AccessibilityService API. While Instagram (`com.instagram.android`) or
Facebook (`com.facebook.katana`) is on screen, the service reads that app's on-screen user
interface: the view hierarchy, view identifiers, scroll positions and visible text such as
account names and captions. The service is limited to these two apps and does not read the
content of any other app; it only checks which app is in the foreground, to know when Instagram
or Facebook is no longer on screen.

The service does nothing until you accept the in-app disclosure shown on first launch.

## How the data is used
Only to provide the features you turn on:

- **Remove Reels** (Instagram and Facebook): recognise that you are watching a Reel and perform a
  back action when you swipe to another one.
- **Block Feed** (Instagram and Facebook): recognise that the Home feed was scrolled below the Stories and
  scroll it back to the top, or tap the Home tab.
- **Block Explore** (Instagram): recognise that the Explore grid was scrolled and scroll it back
  to the top, or tap the Search tab.
- **Free time**: count the minutes you spend on each of these screens, so a block starts only
  after the free minutes you chose and lasts for the block duration you chose (1 to 24 hours).

The screen content is processed in memory, on your device, at the moment it is shown. It is never
recorded, stored or transmitted.

## Data stored on your device
Only your feature settings (on/off, free minutes and block duration), whether you accepted the
in-app accessibility disclosure, and, for each blockable screen, the minutes of free time used and
the time its current block started. These values stay in the app's private storage on your device
and the minutes are reset when each block ends. If
Android backup is turned on, Android may include these settings in your device backup, which is
managed by Google under your backup settings, not by the developer.

## Data collection and sharing
Whitelister collects no personal data and shares nothing with the developer or any third party.
It has no internet permission, no analytics or crash-reporting SDKs, no ads and no account.

## Retention and deletion
No data leaves your device, so the developer holds nothing to retain or delete. You can delete
everything the app stored at any time by clearing its storage (Android Settings > Apps >
Whitelister > Storage) or by uninstalling it.

## Your controls
You can turn every feature off in the app and turn the accessibility service off at any time in
Android Settings > Accessibility > Whitelister.

## Changes
Updates to this policy are published at this address with a new date. If the app starts accessing
data in a new way, you will be asked to accept the in-app disclosure again.

## Contact
For questions about this policy, open an issue at https://github.com/mcap0/whitelister/issues.

Whitelister is an independent project and is not affiliated with, endorsed by or sponsored by
Instagram, Facebook or Meta.
