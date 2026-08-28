# Whitelister

Android app to reclaim control over social media. Starts with Instagram.

## Features

### Remove Reels — Working (stable)
Blocks scrolling between Instagram Reels. You can view a single reel by tapping, but horizontal/vertical scrolling between reels is blocked. When a scroll is detected in the Reels tab, the app performs a back action to keep you on the current reel. **This feature works and must not be modified.**

### Lock Home Feed — Not Working (dev branch only)
Keeps you at the top of the Instagram **For You** feed (Stories only) by bouncing any scroll back to the top; the **Favorites** sub-feed stays scrollable and Reels are untouched. Implemented on the `dev` branch (`WhitelistAccessibilityService.applyBlockHomeFeed`) but currently **non-functional** — another contributor is completing it. (Note: the original "Whitelist Feed" idea — filtering the feed to whitelisted accounts — was deemed infeasible via the AccessibilityService API and was replaced by this approach.)

## How It Works

Whitelister uses Android's **AccessibilityService API** to intercept Instagram's UI events and modify behavior at runtime. No root required.

- **Foreground UI** — Settings screen where you enable the service and toggle features
- **AccessibilityService** — Runs in the background, monitors Instagram, and blocks Reels when enabled. A second feature, "Lock Home Feed", is in development on `dev` but not yet functional.

## Installation

1. Download the APK from [Releases](../../releases)
2. Install the APK on your Android device (API 30+ / Android 11+)
3. Open Whitelister → Tap "Enable Service"
4. You'll be taken to Android Settings → Accessibility → Whitelister → Toggle ON
5. Go back to Whitelister → Toggle "Remove Reels" ON
6. Open Instagram — Reels are now blocked

> **Dev builds only:** the `dev` branch also exposes a "Lock Home Feed" toggle. It is not yet functional — do not rely on it.

## Requirements

- Android 11 (API 30) or higher
- Instagram app installed

## Privacy

- No data is collected or transmitted
- No internet permission required
- All processing happens on-device
- The accessibility service only monitors Instagram (`com.instagram.android`)

## Building from Source

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
