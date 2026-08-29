# AGENTS.md

## Project Overview

Android app to reclaim control over social media. Starts with Instagram; more apps planned later. Open source, Play Store distribution (99 cents). Built function by function.

**Current features (in order):**
1. Remove reels — blocks scrolling in Instagram reels tab (you can view one reel by tapping, but cannot scroll between them). **WORKING — do not modify.**
2. Lock Home Feed — keeps the user at the top of the For You feed (Stories only); Favorites scrollable, Reels untouched. **WORKING (stable, v1.1.1).** (Originally specced as "Whitelist feed" — filtering the feed to whitelisted accounts — which was found infeasible via AccessibilityService and superseded by this approach.)

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
│   │   │   ├── WhitelistAccessibilityService.kt   # Core service — reels blocking + Lock Home Feed (both working)
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

- **Reels blocking: WORKING (stable, `main` v1.0.x and inherited by `dev`)** — blocks reels in BOTH the Reels tab and reels opened from the feed, without breaking the rest of Instagram or closing the app. **Do not modify this logic.**
- **Lock Home Feed: WORKING (v1.1.1)** — implemented in `WhitelistAccessibilityService` (`applyBlockHomeFeed`, plus helpers `findStoriesTray`, `isFeedAtTop`, `isOtherNavTabSelected`). See section below for how it works.
- **Production build config** — `main`: `versionCode 31`, `versionName 1.1.1`. Release uses R8 minify + resource shrink (`isMinifyEnabled = true`, `proguard-rules.pro`, keep rules for the service/activity already present). All `Log.*` calls in the service are gated behind `BuildConfig.DEBUG` (`buildConfig = true`) so a release build logs nothing — keep new log statements gated the same way.
- **Settings persistence** — Reels toggle and Lock-Home-Feed toggle stored in SharedPreferences via `PreferencesManager` (`KEY_BLOCK_HOME_FEED = "block_home_feed_enabled"`).
- **Live UI status** — MainActivity re-checks accessibility service status on `ON_RESUME` so returning from Settings shows "Service Active" immediately

## How Reels Blocking Works

The service listens to `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`, and `TYPE_VIEW_SCROLLED` events from `com.instagram.android`.

1. On window state change (and on a throttled ~500ms basis on window content change) → `detectReelsViewer()` traverses node tree (depth ≤ 15) and returns true if EITHER:
   - a `clips_tab` node has `isSelected == true` (user is on the Reels TAB), OR
   - a full-screen reel container exists: a node whose id contains `clips_viewer_view_pager`, `reel_viewer`, or `clips_video_container` AND whose `getBoundsInScreen()` covers ≥ 85% of the screen. The bounds check is what separates a full-screen reel viewer from an inline feed thumbnail (which previously caused false positives and made Instagram unusable).
2. On scroll (`TYPE_VIEW_SCROLLED`) → if `isInReelsViewer` + reels blocking enabled → `performGlobalAction(GLOBAL_ACTION_BACK)`, then immediately set `isInReelsViewer = false` so the events generated by the back action don't re-trigger a back-loop that closes Instagram.
3. Cooldown of 2.0s prevents rapid repeated back actions.

This is the proven pattern from open source projects (Shorts-Blocker, AntiScroll).

## Lock Home Feed — How It Works (Working, v1.1.1)

**Goal:** lock the For You feed so the user stays at the top (Stories only). Favorites sub-feed must remain scrollable; Reels untouched. **Do not touch the Reels-blocking code.**

**Where it lives:** `WhitelistAccessibilityService.applyBlockHomeFeed()` is invoked from `TYPE_VIEW_SCROLLED`, plus an evaluation pass (no press) on `TYPE_WINDOW_STATE_CHANGED`. Gates: `isInReelsViewer`, the Home button's `isSelected`/`isChecked` state, `isOnFavorites()`, and an at-top guard (`isFeedAtTop()`).

**Mechanism:** when the feed is scrolled down, the service presses the bottom-nav **Home** button, which natively scrolls the feed back to the top. Loop protection:
- "At top" is detected via the **Stories tray** (`findStoriesTray()`: a wide horizontal scrollable with `top > -400 && top < 600`, `width > 50%` of screen). Diagnostic data proved the tray exists at the top, scrolls up, and is then **removed entirely** from the accessibility tree, so tray present + visible (`bottom > 0 && top < bottom`) ⇒ at top.
- A bounce armed: after pressing Home, `sawTopSinceBounce=false`; the feed only bounces again after the tray has been seen at top (`sawTopSinceBounce=true`).
- **Bypass catch:** if the user is scrolled down and the tray was never seen at top since the last bounce, the service presses Home again after the `BOUNCE_SETTLE_MS` (2000ms) settle window, so it fights back while the feed never reaches a settled top.
- Throttle: `BLOCK_HOME_THROTTLE_MS = 1000`.

**History (dev6 → dev15, all non-functional):** Skip-Reels-in-Feed, Disable Autoplay, Auto-Open Favorites (all removed); Home-button bounce without a top-guard → pull-to-refresh loop; `ACTION_SCROLL_BACKWARD` (IG ignores it); `event.scrollY` gate (IG doesn't populate scrollY for feed scrolls). dev17 fixed it with the tray-based top guard + bypass catch above.

**dev18 hotfix ("cold start"):** after closing & reopening Instagram the lock did not engage until the user tapped the bottom-nav Home. Two intertwined causes, both fixed:
- The lock-home state machine (`lastBlockHomeTime`, `lastBounceAt`, `sawTopSinceBounce`) persisted across IG sessions. Now `TYPE_WINDOW_STATE_CHANGED` resets it and runs a no-press evaluation pass (`applyBlockHomeFeed(evaluateOnly = true)`) so a fresh session starts consistent and never presses during the initial (not-yet-rendered) layout.
- On a freshly opened IG no nav tab is reported as `isSelected` yet, so the old "must be selected" gate skipped everything. New logic: if no other bottom-nav tab is selected (`isOtherNavTabSelected()` scans only the bottom band, y > 72% of screen), the feed is treated as Home.

**Hard constraints (learned the hard way):**
- Instagram does **not** populate `AccessibilityEvent.scrollY` for feed scrolls.
- Tapping the Home tab re-selects it → pull-to-refresh → loop if you tap while already at top (hence the tray guard).
- On a cold start Instagram may not report **any** bottom-nav tab as selected. The service treats the feed as Home only when the Home button exists AND no other nav tab in the bottom band (y > 72% of screen) is selected — so it never presses Home while the user is genuinely on Search/Reels/Shop/Profile.
- An AccessibilityService cannot delete/modify IG views, only perform click/scroll/back actions or `GLOBAL_ACTION_BACK`.
- The **Instagram in-app browser** (`BrowserLiteInMainProcessIGActivity`) is a separate window that still reports package `com.instagram.android`, so both features can see its events. The service tracks it on `TYPE_WINDOW_STATE_CHANGED` from the event class name (`"inappbrowser"`), with a tree-scan fallback for `webview`/`browser` classes. While it is active, Reels blocking and Lock Home Feed are both skipped (**v1.1.2**) — otherwise browsing a website triggers a Reels `GLOBAL_ACTION_BACK` or a LockHome Home-press that bounces the user out of the page.
- Overlay approach was rejected by the product owner.

**How to debug:** `adb logcat -s WhitelistService` (debug builds only; release logs nothing) and watch for:
- `LockHome: pressed Home -> bounce to top (reload accepted)` — bounce fired (good).
- `LockHome: at top (Stories tray visible), re-armed` — top guard tripped.
- `LockHome: no nav tab selected yet, assuming Home (cold start)` — cold-start fallback used (evaluate pass re-arms the machine).
- `LockHome: eval-only, would bounce ...` — window-change pass, not pressing (by design).
- `LockHome: on Favorites, skip` / `in Reels viewer, skip` / `not on Home tab, skip` / `Home button not found` — other guards.

## Conventions

- **Language**: Kotlin
- **Code style**: Follow Android/Kotlin official conventions
- **UI**: Jetpack Compose for all app screens
- **Architecture**: MVVM or similar (ViewModel + State)

## Branching & Releases

- **`main`** is the production source of truth. Stable releases are tagged here as `vX.Y.Z` (not pre-release), e.g. `v1.0.4`.
- **`dev`** is for new features. It is never auto-merged into `main`; only an explicit merge (PR) promotes code to `main`.
- **Dev releases** are cut as **pre-release** tags `vX.Y.Z-devN` (e.g. `v1.1.0-dev1`) on the `dev` branch, with the GitHub "pre-release" flag set. They do not affect `main`.
- **Versioning**: `dev` uses the next minor (`1.1.x`) and a `versionCode` kept **higher** than `main` so a dev APK can overwrite the production app when sideloaded for testing (option A). After merging `dev → main`, bump `main`'s `versionCode`/`versionName` and cut a stable release.
- New features start at `1.2.1` on `dev` (`v1.2.0` is the current build on `main`).
- Build: `./gradlew assembleDebug` (set `JAVA_HOME`/`ANDROID_HOME` first). For Play Store a signed AAB is required (`bundleRelease`).
- **Current state:** `main` is at **v1.2.0** (versionCode 33) — target Android 16 (API 36), minSdk Android 6.0 (API 23), AGP 8.9.2 (Play requires API 36 target to keep publishing from 31 Aug 2026). Includes the in-app browser guard from v1.1.2. Production still needs the Play closed-test run (≥12 testers × 14 days). `dev` is **aligned with main at v1.2.0** (versionCode 33); when feature work starts on `dev` again, bump it above `main` (e.g. versionName `1.2.1-dev1`, versionCode 34).

## Publishing a Dev Build (release + APK)

This is the exact procedure used to ship a `dev` pre-release APK to GitHub Releases. Any contributor/AI can repeat it. **Do not push a new build unless the user asked.**

### Prerequisites
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
```
- Must be on the `dev` branch.
- The GitHub PAT is a SECRET. It is **never** committed. Pass it at runtime:
  ```bash
  export GITHUB_PAT=github_pat_xxx   # supplied by the user each run; do NOT hardcode
  ```
  Or store it once in a local `.env.local` (git-ignored — see `.gitignore`) as `GITHUB_PAT=...`; `scripts/publish_dev.sh` auto-sources that file. **The `.env.local` file itself must never be committed or pushed.**
  If `GITHUB_PAT` is still unset, abort and ask the user for it. (Repo is public → a leaked token is auto-revoked.)

### Steps
1. **Bump version** in `app/build.gradle.kts`: `versionCode` must stay **higher than `main`**; `versionName` → `1.1.0-devN` (increment N).
2. **Build (use `clean`!):** the incremental build can falsely report `UP-TO-DATE` and ship stale code:
   ```bash
   ./gradlew clean assembleDebug
   ```
   APK: `app/build/outputs/apk/debug/app-debug.apk`
3. **Commit + push** the version bump (and any feature code):
   ```bash
   git add -A && git commit -m "devN: <what changed>" && git push origin dev
   ```
4. **Cut the pre-release** via GitHub API (read tag/asset name from `versionName`):
   ```bash
   TAG="v1.1.0-devN"   # matches versionName
   cat > /tmp/release_body.json <<'EOF'
   {
     "tag_name": "$TAG",
     "target_commitish": "dev",
     "name": "Whitelister $TAG",
     "body": "## EXPERIMENTAL pre-release (dev)\n\n<what changed>\n\n### Unchanged\nReels blocking (feature 1) works and is untouched.",
     "draft": false,
     "prerelease": true
   }
   EOF
   RID=$(curl -s -H "Authorization: Bearer $GITHUB_PAT" \
     -H "Content-Type: application/json" \
     -d @/tmp/release_body.json \
     https://api.github.com/repos/OWNER/whitelister/releases | grep -m1 '"id"' | grep -oE '[0-9]+')
   echo "release id: $RID"
   ```
5. **Upload the APK** to that release:
   ```bash
   curl -s -H "Authorization: Bearer $GITHUB_PAT" \
     -H "Content-Type: application/vnd.android.package-archive" \
     --data-binary @app/build/outputs/apk/debug/app-debug.apk \
     "https://uploads.github.com/repos/OWNER/whitelister/releases/$RID/assets?name=whitelister-$TAG.apk"
   ```
   (`OWNER` is the GitHub repo owner, e.g. `mcap0`.)
6. Confirm the asset appears at `https://github.com/OWNER/whitelister/releases/tag/$TAG`.

A reusable wrapper lives at `scripts/publish_dev.sh` (same logic, one command). Run it from the repo root:
```bash
GITHUB_PAT=github_pat_xxx ./scripts/publish_dev.sh
```
It refuses to run if not on `dev` or if `GITHUB_PAT` is unset.
