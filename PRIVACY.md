# Whitelister Privacy Policy

Whitelister is an Android app that helps you reduce time spent on Instagram Reels by using Android's AccessibilityService.

## What data the AccessibilityService accesses
To function, Whitelister's AccessibilityService reads the on-screen user interface of Instagram (`com.instagram.android`), including the view hierarchy, view identifiers, and visible text such as account names, post captions, and the labels "Sponsored"/"Patrocinato" or "Suggested for you"/"Consigliato per te". This access is required solely to detect when you are viewing Reels and to hide sponsored, recommended, and reel posts from your feed.

## How the data is used
All processing happens locally on your device, in memory, at the moment the screen is shown. When a sponsored, recommended, or reel post is detected, Whitelister taps Instagram's own "Hide ad" / "Not interested" option on that post. Whitelister does not store, log, or transmit any of this information.

## Data collection and sharing
Whitelister collects no personal data. It does not use the internet, contains no analytics SDKs, and displays no advertisements. No data is shared with the developer or any third party.

## Your controls
You can disable the AccessibilityService at any time in Android Settings > Accessibility. Uninstalling the app removes all local preferences.

## Contact
For questions, contact the developer via the project repository.

Last updated: 2026-08-27
