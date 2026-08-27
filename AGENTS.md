# AGENTS.md

## Project Overview

Android app to reclaim control over social media. Starts with Instagram; more apps planned later. Open source, Play Store distribution (99 cents). Built function by function.

**Current features (in order):**
1. Remove reels — blocks scrolling in Instagram reels tab (you can view one reel by tapping, but cannot scroll between them)
2. Whitelist feed — user adds @account tags to whitelist; all other accounts are hidden from the feed (IN PROGRESS)

## Architecture

- **AccessibilityService API** — the core mechanism. No root required. Service intercepts Instagram's UI events to modify behavior at runtime.
- **Jetpack Compose** — UI for the app's settings/selection screen
- **Gradle Kotlin DSL** — build system
- **Package**: `com.whitelister.app`
- **Min SDK**: API 30 (Android 11)

The app has two layers:
1. **Foreground UI** — settings screen where user selects Instagram and toggles features
2. **AccessibilityService** — runs in background, intercepts Instagram's accessibility events to remove reels / filter feed

## Build Commands

```bash
# Requires JDK 21 and Android SDK
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Install on connected device/emulator
./gradlew installDebug

# Clean build
./gradlew clean
```

## Key Gotchas

- **AccessibilityService must be manually enabled** by the user in Android Settings > Accessibility. The app cannot enable itself.
- **Play Store policies** restrict what accessibility services can do. The app must declare its purpose clearly and must not collect user data.
- **Service may be killed** by Android if not properly foregrounded or if memory is low.
- **Instagram updates** can break element detection at any time. Always test against latest version.
- **Testing requires real device or emulator** with accessibility service enabled — unit tests alone won't cover the service behavior.

## Project Structure

```
whitelister/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/whitelister/app/
│   │   │   ├── MainActivity.kt                    # Compose UI for settings
│   │   │   ├── WhitelistAccessibilityService.kt   # Core service — reels blocking
│   │   │   ├── PreferencesManager.kt              # SharedPreferences wrapper
│   │   │   └── ui/theme/
│   │   │       ├── Color.kt
│   │   │       ├── Theme.kt
│   │   │       └── Type.kt
│   │   └── res/
│   │       ├── values/strings.xml
│   │       └── xml/accessibility_service_config.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── .gitignore
├── README.md
└── AGENTS.md
```

## Current State

- **Reels blocking: WORKING** — detects reels tab via `clips_tab`/`clips_viewer` view IDs, performs `GLOBAL_ACTION_BACK` on scroll with 1.5s cooldown
- **Feed filtering: IN PROGRESS** — button shown in UI but disabled. Network-level approach needed (AccessibilityService cannot reliably hide individual posts).
- **Settings persistence** — Reels toggle and whitelist stored in SharedPreferences via `PreferencesManager`

## How Reels Blocking Works

The service listens to `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`, and `TYPE_VIEW_SCROLLED` events from `com.instagram.android`.

1. On window state change → traverses node tree (depth ≤ 10) looking for view IDs containing `clips_tab`, `clips_viewer`, `reel_viewer`, or `clips_video_container`
2. If reels tab detected + reels blocking enabled → calls `performGlobalAction(GLOBAL_ACTION_BACK)`
3. Cooldown of 1.5s prevents rapid repeated back actions

This is the proven pattern from open source projects (Shorts-Blocker, AntiScroll).

## Conventions

- **Language**: Kotlin
- **Code style**: Follow Android/Kotlin official conventions
- **UI**: Jetpack Compose for all app screens
- **Architecture**: MVVM or similar (ViewModel + State)
