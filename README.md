# Whitelister

Android app to reclaim control over social media. Starts with Instagram.

## Features

### Remove Reels — Working
Blocks scrolling between Instagram Reels. You can view a single reel by tapping, but horizontal/vertical scrolling between reels is blocked. When a scroll is detected in the Reels tab, the app performs a back action to keep you on the current reel.

### Block Feed — Working
Keeps the Instagram Home feed at the top (Stories only). Any scroll down is scrolled straight back programmatically, so the feed is **not reloaded** (the old Home-tab tap, which pull-to-refreshes, is only a fallback).

### Block Explore — New in 1.4.0
Same snap-back on the Search/Explore suggestions grid. Searching and profiles opened from search stay free.

### Daily free time — New in 1.4.0
Each feature can allow N free minutes per day (5–60). The block engages only once today's free time on that surface is used up; usage resets at midnight. "None" = always blocked (default, same behavior as before).

## How It Works

Whitelister uses Android's **AccessibilityService API** to intercept Instagram's UI events and modify behavior at runtime. No root required.

- **Foreground UI** — Settings screen where you enable the service and toggle features
- **AccessibilityService** — Runs in the background, monitors Instagram, and applies the toggles you enabled

## Installation

1. Download the APK from [Releases](../../releases)
2. Install the APK on your Android device (API 30+ / Android 11+)
3. Open Whitelister → Accept the consent → Tap "Enable Service"
4. You'll be taken to Android Settings → Accessibility → Whitelister → Toggle ON
5. Go back to Whitelister → Toggle "Remove Reels", "Block Feed" and/or "Block Explore" ON (optionally pick a daily free time)
6. Open Instagram

## Requirements

- Android 11 (API 30) or higher
- Instagram app installed

## Privacy

- No data is collected or transmitted
- No internet permission required
- All processing happens on-device
- The accessibility service only monitors Instagram (`com.instagram.android`)

## Developing

On the `dev` branch new features are developed and published as pre-release builds.
`main` holds stable releases only. See `AGENTS.md` for the full workflow.

```bash
# Requires JDK 21 and Android SDK
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

# Build debug APK
./gradlew assembleDebug

# APK output
app/build/outputs/apk/debug/app-debug.apk
```

## License

Licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)** with an
additional **non-commercial restriction**. Derivatives must remain open source
under the same AGPL-3.0 license and may not be used for commercial purposes,
except by the copyright holder (mcap0), who reserves the right to distribute
the app commercially (e.g., on the Google Play Store for a price).

See [LICENSE](LICENSE) for the full text and the additional restriction.