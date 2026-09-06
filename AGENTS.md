# AGENTS.md

## Project Overview

Android app to reclaim control over social media. Starts with Instagram; more apps planned later. Open source, Play Store distribution (99 cents). Built function by function.

**Current features (in order):**
1. Remove reels — blocks scrolling in Instagram reels tab (you can view one reel by tapping, but cannot scroll between them). **WORKING — do not modify.**
2. Lock Home Feed — keeps the user at the top of the For You feed (Stories only); Favorites scrollable, Reels untouched. **WORKING (stable, v1.1.1).** (Originally specced as "Whitelist feed" — filtering the feed to whitelisted accounts — which was found infeasible via AccessibilityService and superseded by this approach.)
3. Block Feed — when active, the Home feed cannot be scrolled: any scroll-down is counteracted by pressing the bottom-nav Home tab, which snaps the feed back to the top instantly (accepts pull-to-refresh). **WORKING (dev, uncommitted when this line was written).**

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

- **Reels blocking: WORKING** — blocks the FIRST swipe in **every full-screen reel surface**: the Reels tab, reels opened from the feed, reels opened from Direct messages, reels opened from a profile's reels grid, and reels opened from a chat message. This works for all of them because Instagram funnels every full-screen reel into ONE shared player (`clips_viewer_view_pager`), so the same blocker state machine covers every surface. Viewing one reel by tapping is allowed (the opening settle is absorbed as the baseline); the first page change always triggers BACK. **Do not modify this logic.**
  - **Baseline-stability hotfix (dev, uncommitted when this line was written):** the feed/direct/grid opens used to free-scroll SPORADICALLY because `syncViewerFromTree` wiped `clipsPage` to `-1` on every throttled content-change sync — a wipe between the opening baseline and the user's flick made the swipe land on `clipsPage == -1`, where a swipe-settle (`N→N`) is absorbed as a fresh baseline. Fix: `clipsPage` is reset only when the blocker is NEWLY armed (`!wasEnabled`) off the Reels tab; a disarmed clips scroll re-arms via `syncViewerFromTree("resume")` instead of blanket-absorbing; and `onNonClipsScroll` verifies via a throttled fullscreen-pager tree check before disarming. Validated on Direct: 8/8 fast open+flick cycles blocked on the first swipe, idle open (no swipe) never triggers BACK, Reel-tab reopening still blocks.
  - **Post-fix refinements (also on `dev`, uncommitted — "capture & ship" build for user verification):** four changes that kill the two remaining collateral behaviors (occasional free second scroll, occasional instant-close on open):
    1. **Index-anchored page read (F0).** `cur` is taken from `event.toIndex` when present (resting page), falling back to `maxOf(from, to)`. Revert/backward page moves (e.g. `16->14`) now block too, instead of being absorbed as settles.
    2. **Page retention after BACK (F1).** A blocked swipe records `clipsPage = cur` (+ `clipsPageConfirmed = false`) just before issuing BACK, so the next open is armed with the pager's surviving position — without this, Reel-tab reopen armed with a stale baseline and instant-BACK'd on restore.
    3. **Opening-settle window (F2).** `syncViewerFromTree` stamps `armedAt` on a newly-armed disarmed→armed transition; for the first `OPEN_SETTLE_MS = 400` (baseline-less state, `clipsPage == -1`) every clips event is absorbed, and separately an existing baseline merges subsequent repositsitions within `OPEN_REPOSITION_MS = 100` (`opening reposition merged to page N`). IG snaps the shared pager to the tapped reel as a burst of events within a few dozen ms (e.g. baseline page 10 then `10->11` at +16ms) — those MUST merge, not block, or the reel "closes on open". Real user swipes land later (≥127ms) so they still block.
    4. **Tree-verified cooldown (F3).** The 2.0s BACK-cooldown branch runs `isClipsViewerOpen()` (throttled 400ms tree scan, fail-open) instead of silently dropping events; a swallowed BACK (IG didn't close the viewer) doesn't blind the blocker for the whole cooldown.
    5. **Window-change-gated reset (F4, chat reels).** `TYPE_WINDOW_STATE_CHANGED` no longer blindly resets the session. IG swaps the transparent modal's window MID-viewer on chat-reel opens (`TransparentModalActivity` then `android.view.View`) while the pager is still fullscreen; the old reset re-armed with `clipsPage == -1` right before the user's flick, so the first advance was absorbed as a fresh settle — the chat surface's free first swipe. Fix: the handler checks `isClipsViewerOpen()`; if the pager is still in the tree the armed session (and baseline) is kept (`window change, clips viewer still open, session kept`), and the reset only runs when the viewer is genuinely gone.
    Validation (device): Reel tab shows continuity (`armed page=1` on reopen, `opening reposition merged to page 2`, then `SWIPE 2->3 BACK`); profile grid (thugtai) 11/11 first-flick blocks with no instant-close; feed reels-bar flicks blocked (`SWIPE 12->13`, `15->16`, `17->18` each BACK) and a revert `16->14` blocked via F0; chat-reel rapid up+down cycles block the first flick (`window change ... session kept` → `SWIPE 0->1 BACK`); idle opens never trigger a false BACK.
- **Lock Home Feed: WORKING (v1.1.1)** — implemented in `WhitelistAccessibilityService` (`applyBlockHomeFeed`, plus helpers `findStoriesTray`, `isFeedAtTop`, `isOtherNavTabSelected`). See section below for how it works.
- **Block Feed: WORKING (dev, uncommitted)** — implemented in `WhitelistAccessibilityService` (`blockFeedOnScroll`, `evaluateBlockFeedOnOpen`, `pressHome`, `findFeedTab`, `findStoriesTray`, `isOnHomeTabGivenSelection`, `findSelectedBottomTab`, `isHomeFeedRendered`). See its section below for how it works.
- **Production build config** — `main`: `versionCode 31`, `versionName 1.1.1`. Release uses R8 minify + resource shrink (`isMinifyEnabled = true`, `proguard-rules.pro`, keep rules for the service/activity already present). All `Log.*` calls in the service are gated behind `BuildConfig.DEBUG` (`buildConfig = true`) so a release build logs nothing — keep new log statements gated the same way.
- **Settings persistence** — Reels toggle, Lock-Home-Feed toggle, and Block-Feed toggle stored in SharedPreferences via `PreferencesManager` (`KEY_BLOCK_HOME_FEED = "block_home_feed_enabled"`, `KEY_BLOCK_FEED = "block_feed_enabled"`).
- **Live UI status** — MainActivity re-checks accessibility service status on `ON_RESUME` so returning from Settings shows "Service Active" immediately

## How Reels Blocking Works (v1.2.0+, `dev`)

The service listens to `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`, and `TYPE_VIEW_SCROLLED` events from `com.instagram.android`. All full-screen reels — Reels tab, feed reels, Direct reels, profile reels grids — render in the SAME shared player, a `ViewPager` with id `com.instagram.android:id/clips_viewer_view_pager`. One state machine covers every surface.

1. **Tree arming (the core of the first-swipe fix).** Every reel viewer open emits `TYPE_WINDOW_CONTENT_CHANGED` (throttled to 500ms) or a `TYPE_WINDOW_STATE_CHANGED`; `syncViewerFromTree()` then scans the accessibility tree (depth ≤ 25) and sets `clipsActive = true` the moment it finds a full-screen pager. Ids matched: contains `clips_viewer_view_pager`, `reel_viewer`, or `clips_video_container`, and the node's `getBoundsInScreen()` covers ≥ 60% of the screen (0.6 ratio — high enough to exclude inline thumbnails and grid tiles, low enough to arm during the open animation). Because IG emits content changes as the viewer renders, the blocker is armed BEFORE the user's first flick — no first-swipe free pass.
   - **It NEVER disarms from the tree.** Transient layouts during the open animation briefly report sub-fullscreen bounds; a two-way sync caused false disarms that re-opened the free-pass window. The armed state is only ended by a non-clips feed scroll (`onNonClipsScroll`) or by the BACK issued after a blocked swipe.
2. **Reel-tab vs feed/direct/grid opens.** When the Reels TAB is selected (`clips_tab.isSelected`), the pager keeps its position while hidden, so `clipsPage` is retained across viewer sessions (never reset to `-1`) — reopening the tab is armed with a known baseline and the FIRST swipe blocks immediately, even with no settle. When the viewer opens from the feed/a grid/a DM (tab NOT selected), the pager was repositioned, so a newly-armed blocker resets `clipsPage` to `-1` and re-establishes it from the opening settle. Once armed, `clipsPage` is NEVER wiped again: later content-change syncs (which IG emits continuously while the viewer is open) keep the baseline, or a fast first swipe would land on `clipsPage == -1` and be misread as a settle — the sporadic free-scroll bug.
3. **Scroll handling (`onClipsPagerScroll`).** Only scrolls whose source id == `clips_viewer_view_pager` are treated as reel navigation. `cur` is the resting page: `event.toIndex` when present, else `maxOf(fromIndex, toIndex)` — anchoring on the resting page is what makes revert/backward moves (e.g. `16->14`) block too. A page change away from `clipsPage` = a swipe → `performGlobalAction(GLOBAL_ACTION_BACK)`, cooldown 2.0s, `clipsActive = false`, and `clipsPage` is kept at `cur` (unconfirmed) so the next open is armed with the surviving position. A `clipsPage == -1` state (newly armed off the tab) absorbs everything within the `OPEN_SETTLE_MS = 400` window as the opening baseline; after that window, `fromIndex != toIndex` = a genuine swipe with no baseline and blocks outright. An existing baseline merges further reposition events within `OPEN_REPOSITION_MS = 100` of `armedAt` (the pager's opening snap is often several events, e.g. page 10 then `10->11` at +16ms — blocking those is the instant-close false positive). If the blocker is unexpectedly disarmed while the viewer is open, a scroll re-arms it from the tree (`syncViewerFromTree("resume")`). Reel-tab opens are continuous (no absorb, page retained, but the re-arm still starts the reposition-merge window); IG's programmatic auto-advance on profile/feed playlists arrives as settles and is absorbed as baselines, so it does NOT trigger BACK — the first genuine user swipe still does. IG does not reliably emit intermediate drag events (swipes can arrive as a single final settle), which is exactly why the `cur != clipsPage` comparison (not the from/to pair) is the block signal.
4. **Disarm is verified, not assumed.** `onNonClipsScroll` no longer disarms on any non-clips scroll — a throttled (400ms) fullscreen-pager tree check (`isFullscreenClipsPager`) confirms the viewer actually left before `resetClipsSession`; inner scrollables (comment sheet, DM lists, generic `recycler_views` inside the viewer) can't falsely disarm an open viewer and reopen the free-pass window.
5. **Cooldown of 2.0s** prevents rapid repeated back actions and the BACK-triggered event storm. The cooldown branch does NOT silently drop events — it runs `isClipsViewerOpen()` (throttled 400ms tree scan, failing open when the root is transient) so a swallowed BACK re-arms and blocks again instead of blinding the blocker until the cooldown expires.

This is the proven pattern from open source projects (Shorts-Blocker, AntiScroll), combined with the tree arming + page-continuity + settle-absorb design that fixed the Reels-tab "always scrolls once" bug (dev ~v1.2.x).

## Block Feed — How It Works (Working, dev)

**Goal:** when active, the Home feed cannot be scrolled down from the top. Any scroll-down is counteracted by pressing the **bottom-nav Home tab**, which snaps the feed back to the top instantly. The re-tap re-selects Home (✔) and **does trigger pull-to-refresh** — the reload is accepted by the product rule in exchange for the instant snap-back (a `dispatchGesture` scroll-up swipe was tried first but felt too slow and was removed; the scroll-up no longer needed `canPerformGestures`, so that flag was removed from `accessibility_service_config.xml` too). **Do not use `ACTION_SCROLL_TO_TOP`** (IG ignores it).

**Where it lives:** `WhitelistAccessibilityService.blockFeedOnScroll()` is invoked from `onNonClipsScroll()` (non-clips `TYPE_VIEW_SCROLLED`), plus an on-open evaluation `evaluateBlockFeedOnOpen()` from `TYPE_WINDOW_STATE_CHANGED`. Gates: throttle → the full-screen clips check (`isClipsViewerOpen()` — never fight a reel swipe with a Home press) → Home-tab isolation (`isOnHomeTabGivenSelection`) → feed-rendered gate (`isHomeFeedRendered`) → at-top guard (`findStoriesTray`).

**Mechanism:**
- `blockFeedOnScroll()`: throttled to `BLOCK_FEED_THROTTLE_MS = 500`, verifies no full-screen reel pager is open, verifies the user is on the Home tab, and then — if the Stories tray is NOT currently expanded (feed scrolled down) — posts `pressHomeRunnable` to the main `Handler` (`BLOCK_FEED_PRESS_DELAY_MS = 150`, debounced: rapid scroll events reschedule it, so one press fires per scroll burst).
- `pressHome()`: finds the `com.instagram.android:id/feed_tab` node (`findFeedTab`) and `performAction(ACTION_CLICK)` — the same node the user taps by hand, so IG snaps to top natively.
- At-top detection reuses the **Stories tray**: unlike the Lock-Home-Feed era, the tray is NOT removed when scrolled — it **collapses into a thin sticky strip**. So `findStoriesTray()` requires `top > -400 && top < 600`, `width > 50%` screen, AND `trayHeight / screenHeight >= BLOCK_FEED_TRAY_MIN_HEIGHT_RATIO` (0.10). Diagnostics: expanded tray at top ≈ 345px (~14% of 2424px screen); collapsed strip ≈ 134px (~5.5%). When the tray is expanded → `at top`, return early (no press) — this is the loop guard that stops re-tapping Home while already at top.

**Refresh-loop guard (the key constraint):** pressing Home re-selects the already-selected Home tab → pull-to-refresh, during which the tray collapses and IG emits scroll events. A naive rule would re-press on every one of those events → infinite refresh loop. Following the Lock-Home-Feed `sawTopSinceBounce` pattern: the at-top branch sets `sawTopSinceBlockPress = true`; each press sets it `false`; a press is refused while `!sawTopSinceBlockPress` UNLESS `BLOCK_FEED_BOUNCE_SETTLE_MS = 2000` elapsed (the hard bypass catch, in case the tray never re-appears). So: one press → feed at top → refresh spins with tray hidden → tray re-expands (re-armed) → the next genuine user scroll-down can press once more.

**Home-tab isolation:** `isOnHomeTabGivenSelection(root)` returns true if the `feed_tab` is `selected`, OR if **no** bottom-nav tab is selected (IG cold start — treat as Home). `findSelectedBottomTab` scans only the bottom band (y > 72% of screen) for any other `_tab` that is selected → not Home. This makes Block Feed skip on Profile/Reels/Search/Direct, and its Home press never hijacks those surfaces. Verified: scrolling on Profile logs `BlockFeed: not on Home tab, skip` with zero interference.

**Cold-start hotfix (the "doesn't work when opening the app" bug):** on a fresh IG start (process killed or app reopened) NO bottom tab is reported `selected` during — and often after — the initial render, so the old "must be selected" gate treated every cold-start scroll as "not on Home tab" and Block Feed never engaged until the user tapped a tab. Two fixes:
- **The assume-Home fallback was dead code.** `isOnHomeTabGivenSelection` did `val other = findSelectedBottomTab(root); return other == null` — but `findSelectedBottomTab` returned non-nullable `Boolean`, so `other == null` was ALWAYS false and the fallback NEVER returned true. It now returns `Boolean?` (null = no other tab selected) and, per the doc's original intent, only counts `_tab` nodes inside the bottom band (y ≥ 72%) — a transiently-selected mid-tree `_tab` (stories sub-tabs, sheets, carousels) can no longer clear the cold-start assume-Home either. Verified: cold start → first scroll now `pressed Home` immediately.
- **Feed-rendered gate.** Assumed-Home must also prove the actual feed is on screen: `blockFeedOnScroll` and the open-eval both require `isHomeFeedRendered(root)` (the `android:id/list` / `swipeable_nav_view_pager_inner_recycler_view` feed list `isVisibleToUser` AND `feed_tab` present). Otherwise a cold-opened-but-restored Profile/Reels (also no tab selected during its fade-in) would read as "assume Home" and get hijacked to the feed. Verified: Profile still `not on Home tab, skip`.

**On-open evaluation (`evaluateBlockFeedOnOpen`):** `TYPE_WINDOW_STATE_CHANGED` with class name `com.instagram.mainactivity.InstagramMainActivity` (or IG's generic `android.widget.FrameLayout` content root) schedules the eval after `BLOCK_FEED_OPEN_SETTLE_MS = 700` (retry at +1200ms if the feed is still rendering, `openEvalAttempts`). It re-arms `sawTopSinceBlockPress = true` for the fresh session, and if the feed rendered scrolled-down (tray collapse restored from the last session) presses Home once to snap back — a scroll event never fires for a *restored* scroll position, so the scroll path alone would leave it mid-feed. At top → `opened at top, skip` (NO reload on a clean open). Bounded by `BLOCK_FEED_OPEN_MIN_INTERVAL_MS = 2500` so a Home↔surface↔Home cycle can't double-press.

**Constraints discovered empirically:**
- The Home-tab press triggers pull-to-refresh (reload). The 150ms press delay + near-instant snap feels immediate; the 500ms throttle caps re-presses.
- The at-top tray guard must be strict (height ratio ≥ 10%) — a sloppier check would treat the collapsed strip as "at top", and (worse) the refresh-spinner state as "at top", breaking the loop guard.
- Re-press during the reload is suppressed by the `sawTopSinceBlockPress` re-arm gate (see above).

**How to debug:** `adb logcat -s WhitelistService` (debug builds only; release logs nothing) and watch for:
- `BlockFeed: pressed Home -> snap to top (reload accepted) clicked=true` — a scroll-down was counteracted.
- `BlockFeed: at top (Stories tray visible), re-armed` — feed re-settled at top; the guard re-arms so the next scroll can press again.
- `BlockFeed: refresh-settle guard, skip` — a press was suppressed while the pull-to-refresh was still settling (loop guard working).
- `BlockFeed: not on Home tab, skip` — isolation working (Profile/Reels/Search/Direct).
- `BlockFeed: home assumed but feed not rendered, skip` — cold-start/restored-surface safety gate.
- `BlockFeed: opened at top, skip` — app opened clean, NO reload fired.
- `BlockFeed: opened mid-feed -> snap to top` — app opened with a restored scroll, snapped once.
- `BlockFeed: clips viewer open, skip` — reel surface guard.
- `BlockFeed: Home button not found` — `feed_tab` node missing from the tree.

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
