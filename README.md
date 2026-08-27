# Whitelister

Android app to reclaim control over social media. Starts with Instagram.

## Features

### Remove Reels (Working)
Blocks scrolling between Instagram Reels. You can view a single reel by tapping, but horizontal/vertical scrolling between reels is blocked. When a scroll is detected in the Reels tab, the app performs a back action to keep you on the current reel.

### Whitelist Feed (In Progress)
Filter your Instagram feed to only show posts from whitelisted accounts. This feature is currently under development.

## How It Works

Whitelister uses Android's **AccessibilityService API** to intercept Instagram's UI events and modify behavior at runtime. No root required.

- **Foreground UI** — Settings screen where you enable the service and toggle features
- **AccessibilityService** — Runs in the background, monitors Instagram, and blocks Reels when enabled

## Installation

1. Download the APK from [Releases](../../releases)
2. Install the APK on your Android device (API 30+ / Android 11+)
3. Open Whitelister → Tap "Enable Service"
4. You'll be taken to Android Settings → Accessibility → Whitelister → Toggle ON
5. Go back to Whitelister → Toggle "Remove Reels" ON
6. Open Instagram — Reels are now blocked

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

Open source — see [LICENSE](LICENSE) for details.
