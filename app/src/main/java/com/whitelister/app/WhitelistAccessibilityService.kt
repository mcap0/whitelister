package com.whitelister.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhitelistAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhitelistService"
        private const val ACTION_DUMP = "com.whitelister.app.DUMP"

        private const val CLIPS_PAGER_ID = "com.instagram.android:id/clips_viewer_view_pager"
        private const val FEED_LIST_ID = "android:id/list"

        /** Loop protection only (not a grace period): pause BACK re-actions this long after we issue one. */
        private const val BACK_COOLDOWN_MS = 2000L

        /** Throttle for the accessibility-tree viewer scans on content changes. */
        private const val TREE_SYNC_THROTTLE_MS = 500L

        /** Throttle for the disarm check on non-clips scrolls (keeps it cheap). */
        private const val DISARM_CHECK_THROTTLE_MS = 400L

        /**
         * After the reel viewer is first detected, IG's opening emit (the programmatic
         * reposition to the tapped reel, often reported as from != to) must not be read as a
         * user swipe, or the reel closes on open. Events landing inside this window while no
         * baseline is known are absorbed as the opening baseline instead of blocked.
         */
        private const val OPEN_SETTLE_MS = 400L

        /**
         * An opening baseline can be followed within a few dozen ms by MORE reposition events
         * (IG snaps the shared pager to the tapped reel as e.g. 10 then 10->11). Those must
         * merge into the baseline, not block the reel. Real user swipes settle later than this
         * (human reaction time), so this window does not grant a free pass.
         */
        private const val OPEN_REPOSITION_MS = 100L

        /** A node is "fullscreen" (the reel viewer is engaged) when it covers this share of the screen. */
        private const val FULLSCREEN_RATIO = 0.6f

        /** Throttle for Block Feed's press-Home bounce (keeps it cheap, avoids re-press storms). */
        private const val BLOCK_FEED_THROTTLE_MS = 500L

        /** The Stories tray sits at the very top of the home feed. When visible, we are at top. */
        private const val BLOCK_FEED_TRAY_TOP = -400
        private const val BLOCK_FEED_TRAY_BOTTOM = 600
        private const val BLOCK_FEED_TRAY_WIDTH_RATIO = 0.5f

        /**
         * Max height (px) a node may be and still be considered the Stories tray. The tray is a
         * short row (~1-2 hundred px tall) nested inside the feed list; the feed list itself
         * spans the full screen (0, 152 - 0, 2235) and would otherwise false-match the at-top
         * test. 600 is well above the tray's real height yet far below any full-screen feed.
         */
        private const val BLOCK_FEED_TRAY_MAX_HEIGHT = 600f

        /**
         * When the feed scrolls down, the Stories tray COLLAPSES into a thin sticky strip rather
         * than being removed. Expanded at top it is ~14% of the screen height; collapsed it is
         * ~5.5%. Requiring at least 10% of the screen keeps the collapsed strip from reading as
         * "at top", so Block Feed still fires while the mini-strip shows.
         */
        private const val BLOCK_FEED_TRAY_MIN_HEIGHT_RATIO = 0.10f

        /**
         * Wait this long after a deviating feed scroll before tapping the Home tab. Short enough to
         * feel instant, long enough that a tap is not swallowed by the user's own ongoing fling.
         */
        private const val BLOCK_FEED_PRESS_DELAY_MS = 150L

        /**
         * After a Home-tab press the feed snaps to top and Instagram pulls-to-refresh (the
         * reload shows the collapsed tray, so the at-top guard can't tell it is really at top).
         * While the refresh settles, further presses are suppressed; only once the expanded tray
         * is seen at top again (re-arm) -- or this hard timeout expires -- can Block Feed press
         * Home once more. This is the loop guard that stops a pull-to-refresh re-press storm.
         */
        private const val BLOCK_FEED_BOUNCE_SETTLE_MS = 2000L

        /** Wait this long after the Home surface appears before evaluating the feed position. */
        private const val BLOCK_FEED_OPEN_SETTLE_MS = 700L

        /** Second-chance delay when the first open-eval found the feed still rendering. */
        private const val BLOCK_FEED_OPEN_RETRY_MS = 1200L

        /** Minimum gap between open-evaluation presses: one snap-back per surface appearance. */
        private const val BLOCK_FEED_OPEN_MIN_INTERVAL_MS = 2500L
    }

    // --- Reels-blocking state machine (empirical, source-based) ---
    private var clipsActive = false
    // The clips pager's current reel index. It is kept across viewer sessions because
    // Instagram's reel viewer pager retains its position while hidden, so the blocker
    // is armed with a known baseline the moment the viewer appears again. -1 = unknown
    // (cold start / feed-open reposition).
    private var clipsPage = -1
    private var clipsPageConfirmed = false
    private var lastBackAt = 0L
    private var lastTreeSyncAt = 0L
    private var lastDisarmCheckAt = 0L
    // When the blocker was last armed (disarmed -> armed). Gates the OPEN_SETTLE_MS absorb;
    // NOT refreshed on re-confirmations, or a long-rendering open would keep the window open.
    private var armedAt = 0L

    // --- Block Feed state machine ---
    // Last time Block Feed issued a press-Home bounce. Throttled to avoid re-press storms while
    // the feed settles back to the top.
    private var lastBlockFeedAt = 0L
    // Loop guard: after a press-Home bounce (which triggers pull-to-refresh, hiding the tray),
    // the feed must be seen back at top (expanded tray) before Block Feed presses Home again.
    private var sawTopSinceBlockPress = false
    private var lastBlockFeedPressAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pressHomeRunnable = Runnable {
        pressHome()
    }
    private val openFeedEvalRunnable = Runnable {
        evaluateBlockFeedOnOpen()
    }
    private var openEvalAttempts = 0

    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_DUMP) {
                lastTreeDump = 0L
                dumpTree("ADB")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        setServiceInfo(info)
        val filter = IntentFilter(ACTION_DUMP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dumpReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(dumpReceiver, filter)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != "com.instagram.android") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "WINDOW_STATE className=${event.className}")
                    dumpTree("WINDOW_STATE")
                }
                // A window change while the clips viewer is still present MUST NOT wipe the
                // session: chat-reel opens swap the transparent modal's window MID-viewer
                // (e.g. TransparentModalActivity -> android.view.View) with the pager still
                // in the tree, and the reset re-arms with clipsPage = -1 right before the
                // user's flick, so the advance lands on clipsPage == -1 and is absorbed as a
                // fresh "settle" -- the chat surface's free first swipe. Reset only when the
                // viewer is genuinely gone from the tree.
                if (isClipsViewerOpen()) {
                    syncViewerFromTree("window")
                    if (BuildConfig.DEBUG) Log.d(TAG, "blocker: window change, clips viewer still open, session kept")
                } else {
                    resetClipsSession("app window change")
                    syncViewerFromTree("window")
                }
                // Block Feed: on the Home surface appearing (reopen / cold start) evaluate the
                // feed position so a restored-scrolled feed snaps to top without needing a
                // scroll event. Class-gated to the main feed window (and IG's generic content
                // root); everything else (sheets, DM, profile) is filtered by the eval's own
                // feed-rendered + at-top gates.
                if (PreferencesManager.isBlockFeedEnabled(this)) {
                    val cn = event.className?.toString()
                    if (cn != null && (cn.contains("mainactivity") || cn == "android.widget.FrameLayout")) {
                        openEvalAttempts = 0
                        mainHandler.removeCallbacks(openFeedEvalRunnable)
                        mainHandler.postDelayed(openFeedEvalRunnable, BLOCK_FEED_OPEN_SETTLE_MS)
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastTreeSyncAt >= TREE_SYNC_THROTTLE_MS) {
                    lastTreeSyncAt = now
                    syncViewerFromTree("content")
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                onViewScrolled(event)
            }
        }
    }

    private fun onViewScrolled(event: AccessibilityEvent) {
        if (BuildConfig.DEBUG) logScrollEvent(event)
        val src = event.source ?: return
        val rid = src.viewIdResourceName

        if (rid == CLIPS_PAGER_ID) {
            onClipsPagerScroll(event)
        } else {
            onNonClipsScroll(rid)
        }
    }

    private fun onClipsPagerScroll(event: AccessibilityEvent) {
        // Anchor on toIndex: it is the page the pager RESTED at after this motion, while
        // fromIndex is the page left behind. maxOf(from,to) makes a revert swipe (2->1) look
        // like the current page and grants a free pass. Settles (from==to) are unaffected.
        // Guard toIndex == -1 (some IG events carry no index) with a maxOf fallback.
        val cur = if (event.toIndex >= 0) event.toIndex else maxOf(event.fromIndex, event.toIndex)
        val now = System.currentTimeMillis()

        if (!clipsActive) {
            // A stray non-clips scroll (feed/direct/grid inner scrollables) or a raced
            // tree sync can leave the blocker un-armed while the viewer is open. Re-arm
            // from the tree before deciding: never blanket-absorb, or a fast first swipe
            // gets a free pass.
            syncViewerFromTree("resume")
            if (clipsActive) {
                // A resume is a freshly-armed session whose opening emit may have been
                // missed; do not let THIS event hide inside the open-settle window.
                armedAt = 0
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: re-armed via tree (page=$clipsPage)")
                // Fall through to the baseline logic below.
            } else {
                // The tree scan could not confirm the viewer (root null / transient
                // window). Absorb this event as the baseline rather than guessing.
                if (cur < 0) return
                clipsActive = true
                clipsPage = cur
                clipsPageConfirmed = event.fromIndex == event.toIndex
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: reels viewer open at page $cur")
                return
            }
        } else if (now - lastBackAt < BACK_COOLDOWN_MS) {
            // A BACK was just issued. Only keep ignoring scrolls while the viewer is actually
            // gone: a BACK that got eaten (sheet collapse / transition race) leaves the pager
            // open, and the user's next swipes must still be blocked. A real page charge on a
            // still-open viewer below falls through and blocks again.
            if (!isClipsViewerOpen()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: cooldown after BACK, viewer gone, ignoring scroll")
                return
            }
            if (clipsPage >= 0 && cur == clipsPage) return
        }

        if (clipsPage == -1) {
            // Baseline unknown. When the pager was just armed (cold tab start, or feed/grid/DM
            // open), IG plays a programmatic reposition to the tapped reel as SEVERAL page-
            // advancing events (e.g. 10 then 10->11); the last one is where the pager rests.
            // Inside the OPEN_SETTLE_MS window every one of those is part of the open -- absorb
            // them all as the baseline. Blocking one is the "reel closes on open" false
            // positive. After the window, a from != to event with no baseline is a genuine
            // first swipe on a silently-rendered viewer and must be blocked outright.
            if (cur >= 0 && now - armedAt <= OPEN_SETTLE_MS) {
                val prev = clipsPage
                clipsPage = cur
                clipsPageConfirmed = event.fromIndex == event.toIndex
                if (BuildConfig.DEBUG && prev != cur) {
                    Log.d(TAG, "blocker: opening baseline (settle window) at page $cur")
                }
                return
            }
            if (cur < 0) return
            if (event.fromIndex == event.toIndex) {
                // Late settle, still no baseline: nothing to compare against. Absorb it -- the
                // no-wipe rule holds, so this cannot reopen the periodic free-pass.
                clipsPage = cur
                clipsPageConfirmed = true
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: late baseline at page $cur")
                return
            }
            if (cur < 0) return
            if (PreferencesManager.isReelsBlockingEnabled(this)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: SWIPE (no baseline) ${event.fromIndex}->$cur, issuing BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastBackAt = now
                clipsActive = false
                clipsPage = cur
                clipsPageConfirmed = false
            } else {
                clipsPage = cur
                clipsPageConfirmed = false
            }
            return
        }

        if (clipsPage >= 0 && cur == clipsPage) {
            if (!clipsPageConfirmed) {
                clipsPageConfirmed = true
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: reels viewer settled at page $cur")
            }
            return
        }

        // Page changed away from the baseline. Inside the first ~100ms after arming, a page
        // move with an existing baseline is still the opening reposition: IG snaps the shared
        // pager to the tapped reel as several events (e.g. 10 then 10->11) within a few dozen
        // ms. Absorb it as a baseline merge -- blocking it is the "reel closes on open" false
        // positive. Real user swipes land later than this (human reaction), so they block.
        if (now - armedAt <= OPEN_REPOSITION_MS) {
            clipsPage = cur
            clipsPageConfirmed = false
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: opening reposition merged to page $cur")
            return
        }

        // Any deviation from the baseline is a genuine swipe. Record the pager's surviving
        // position so the next open is armed with a matching baseline -- without this, the
        // Reel-tab reopens with clipsPage one page behind and instant-BACKs on restore.
        if (PreferencesManager.isReelsBlockingEnabled(this)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: SWIPE $clipsPage->$cur, issuing BACK")
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBackAt = now
            clipsActive = false
            clipsPage = cur
            clipsPageConfirmed = false
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: SWIPE $clipsPage->$cur (blocking disabled)")
            clipsPage = cur
            clipsPageConfirmed = false
        }
    }

    /** True when the full-screen reel viewer is still in the accessibility tree (throttled scan). */
    private fun isClipsViewerOpen(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDisarmCheckAt < DISARM_CHECK_THROTTLE_MS) return true
        lastDisarmCheckAt = now
        val root = getRootInActiveWindow() ?: return true
        try {
            return isFullscreenClipsPager(root, 0)
        } finally {
            root.recycle()
        }
    }

    private fun onNonClipsScroll(rid: String?) {
        // Block Feed: any non-clips scroll while the home feed can be engaged should send us
        // back to the top. This runs even if reels blocking's clips session is not active.
        if (PreferencesManager.isBlockFeedEnabled(this)) {
            blockFeedOnScroll()
        }
        if (!clipsActive) return
        // Do not disarm while the full-screen reel viewer is still present: inner
        // scrollables (comment sheet, DM lists, generic recycler_views inside the
        // viewer) fire scroll events while it is open, and a false disarm lets the
        // next reel swipe be absorbed as a fresh baseline -- the sporadic free
        // scroll across feed/direct/profile reels.
        if (isClipsViewerOpen()) return
        if (BuildConfig.DEBUG) Log.d(TAG, "blocker: reels viewer closed (feed scroll source=$rid)")
        resetClipsSession("feed scroll")
    }

    /**
     * Block Feed: when the user scrolls the Home feed away from the top, press the bottom-nav
     * Home tab so the feed snaps back to the top. A re-tap of the already-selected Home tab
     * triggers a pull-to-refresh (reload) -- the product rule accepts the reload in exchange
     * for the instant snap-back (much faster than the dispatchGesture scroll-up that replaced).
     *
     * The at-top test is the Stories tray: expanded (>10% screen height) it exists only at the
     * very top of the feed. While the tray is NOT expanded we are scrolled down and may press.
     * During the reload the tray is hidden, so a re-arm gate (sawTopSinceBlockPress) prevents
     * re-pressing until the feed is genuinely seen back at top -- that stops the refresh loop.
     */
    private fun blockFeedOnScroll() {
        val now = System.currentTimeMillis()
        if (now - lastBlockFeedAt < BLOCK_FEED_THROTTLE_MS) return
        // Never hijack scrolls inside the full-screen reel viewer (comments sheet, DM list reels,
        // etc.) -- the clips pager is already handled by reels blocking.
        if (isClipsViewerOpen()) return
        val root = getRootInActiveWindow() ?: return
        try {
            // Only engage on the Home feed tab. On Search/Reels/Shop/Profile the user must be free
            // to scroll. Fall back to "assume Home" when no bottom tab is reported selected yet
            // (IG's cold-start state) -- but only when the actual Home feed is on screen, so a
            // restored-other-tab surface (no tab selected yet during its fade-in) is not hijacked.
            if (!isOnHomeTabGivenSelection(root)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: not on Home tab, skip")
                return
            }
            if (!isHomeFeedRendered(root)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: home assumed but feed not rendered, skip")
                return
            }
            if (findStoriesTray(root, 0)) {
                sawTopSinceBlockPress = true
                if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: at top (Stories tray visible), re-armed")
                return
            }
        } finally {
            root.recycle()
        }
        // Not at top. But if we last pressed Home, IG is still mid pull-to-refresh (tray hidden):
        // wait for the tray to be seen at top again -- or for the hard settle timeout -- before
        // pressing again, otherwise every scroll event during the reload re-presses → refresh loop.
        if (!sawTopSinceBlockPress && now - lastBlockFeedPressAt < BLOCK_FEED_BOUNCE_SETTLE_MS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: refresh-settle guard, skip")
            return
        }
        lastBlockFeedAt = now
        lastBlockFeedPressAt = now
        sawTopSinceBlockPress = false
        // Tap AFTER the current scroll settles: injecting mid-motion gets merged into the user's
        // ongoing drag and swallowed. The delay lets IG reach its resting position first.
        mainHandler.removeCallbacks(pressHomeRunnable)
        mainHandler.postDelayed(pressHomeRunnable, BLOCK_FEED_PRESS_DELAY_MS)
    }

    /**
     * Block Feed, on app open: if the Home feed appeared scrolled down (IG restores the previous
     * session's scroll) snap it back to the top -- no scroll event fires for a restored position,
     * so the scroll-driven path alone would leave it mid-feed. Also re-arms the loop-guard state
     * for a fresh session so a stale mid-refresh flag from the last session can't suppress the
     * first bounce. Never presses while the feed is rendered at top (no reload on a clean open).
     */
    private fun evaluateBlockFeedOnOpen() {
        if (!PreferencesManager.isBlockFeedEnabled(this)) return
        val now = System.currentTimeMillis()
        val root = getRootInActiveWindow() ?: return
        try {
            // Fresh session: the feed is either seen at top (re-arms) or about to be bounced.
            if (isOnHomeTabGivenSelection(root)) {
                if (!isHomeFeedRendered(root)) {
                    // Surface still mid-render (splash/transition root). Give the layout one or
                    // two more chances to appear before giving up on this window change.
                    if (openEvalAttempts < 2) {
                        openEvalAttempts++
                        mainHandler.removeCallbacks(openFeedEvalRunnable)
                        mainHandler.postDelayed(openFeedEvalRunnable, BLOCK_FEED_OPEN_RETRY_MS)
                    }
                    return
                }
                if (findStoriesTray(root, 0)) {
                    sawTopSinceBlockPress = true
                    if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: opened at top, skip")
                    return
                }
                // Opened scrolled-down: snap back once, respecting throttle and the min interval
                // between open-presses (a Home <-> surface <-> Home cycle must not double-press).
                if (now - lastBlockFeedPressAt < BLOCK_FEED_OPEN_MIN_INTERVAL_MS) return
                if (now - lastBlockFeedAt < BLOCK_FEED_THROTTLE_MS) return
                lastBlockFeedAt = now
                lastBlockFeedPressAt = now
                sawTopSinceBlockPress = false
                if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: opened mid-feed -> snap to top")
                mainHandler.removeCallbacks(pressHomeRunnable)
                mainHandler.postDelayed(pressHomeRunnable, BLOCK_FEED_PRESS_DELAY_MS)
            }
            // Not Home (another tab genuinely selected): leave the restored surface alone.
        } finally {
            root.recycle()
        }
    }

    /**
     * True when the actual Home feed surface is present and rendered: the main feed list is
     * visible AND the bottom-nav Home tab node exists (so pressHome can act). This is what stops
     * the open-evaluation from pressing Home into a Profile/DM/comments/splash surface, which
     * would otherwise also qualify as "no bottom tab selected" on a cold start.
     */
    private fun isHomeFeedRendered(node: AccessibilityNodeInfo?): Boolean {
        if (!isFeedListVisible(node, 0)) return false
        return findFeedTab(node, 0) != null
    }

    private fun isFeedListVisible(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25) return false
        val rid = node.viewIdResourceName
        if (rid == "android:id/list" || rid == "com.instagram.android:id/swipeable_nav_view_pager_inner_recycler_view") {
            if (node.isVisibleToUser) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isFeedListVisible(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    /**
     * True when the active window is the Home feed. Home is selected (`feed_tab` selected), OR
     * on IG cold start no bottom tab is reported selected yet (then we assume Home so the lock
     * can engage immediately). Returns false when a different tab is confirmed selected.
     */
    private fun isOnHomeFeedTab(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25) return false
        val rid = node.viewIdResourceName
        if (rid != null && rid == "com.instagram.android:id/feed_tab") {
            if (node.isSelected || node.isChecked) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = isOnHomeFeedTab(child, depth + 1)
            child.recycle()
            if (result) return true
        }
        return false
    }

    /**
     * Home is assumed when the Home tab is selected OR when NO other bottom tab is reported
     * selected (IG cold start reports no selection; the Lock-Home-Feed precedent treats that as
     * Home so the lock engages immediately).
     */
    private fun isOnHomeTabGivenSelection(root: AccessibilityNodeInfo): Boolean {
        if (isOnHomeFeedTab(root, 0)) return true
        val otherSelected = findSelectedBottomTab(root, 0)
        return otherSelected == null
    }

    /**
     * True when a bottom-nav tab OTHER than feed_tab is reported selected. `null` when no such
     * tab is selected anywhere. Only nodes in the bottom band (y >= 72% of screen) count: a
     * transiently-selected `_tab` mid-tree (stories sub-tabs, sheets, carousels) must not clear
     * the cold-start "assume Home" fallback.
     */
    private fun findSelectedBottomTab(node: AccessibilityNodeInfo?, depth: Int): Boolean? {
        if (node == null || depth > 25) return null
        val rid = node.viewIdResourceName
        if (rid != null && rid.endsWith("_tab") && rid != "com.instagram.android:id/feed_tab") {
            if (node.isSelected || node.isChecked) {
                val b = Rect()
                node.getBoundsInScreen(b)
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                if (screenHeight > 0 && b.top / screenHeight >= 0.72f) return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSelectedBottomTab(child, depth + 1)
            child.recycle()
            if (found == true) return true
        }
        return null
    }

    /** Click the bottom-nav Home tab so the feed snaps back to the top. */
    private fun pressHome() {
        try {
            val root = getRootInActiveWindow()
            if (root == null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: press skipped, no active window")
                return
            }
            val homeTab = findFeedTab(root, 0)
            if (homeTab == null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: Home button not found")
                root.recycle()
                return
            }
            val clicked = homeTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: pressed Home -> snap to top (reload accepted) clicked=$clicked")
            homeTab.recycle()
            root.recycle()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "pressHome error", e)
        }
    }

    /** Finds the bottom-nav Home tab node (com.instagram.android:id/feed_tab). */
    private fun findFeedTab(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        if (node.viewIdResourceName == "com.instagram.android:id/feed_tab") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFeedTab(child, depth + 1)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /**
     * The Stories tray is a wide horizontal scrollable that appears only at the top of the
     * Home feed (it scrolls up and is then removed from the tree entirely). Presence + visible
     * on-screen bounds = we are at the top. isVisibleToUser is critical: Instagram pre-renders
     * off-screen pages (profile highlights trays, adjacent tab surfaces) in the same tree with
     * bounds that pass the at-top test but are NOT shown; matching those is a false "at top".
     */
    private fun findStoriesTray(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25) return false
        if (node.isScrollable && node.isVisibleToUser) {
            val b = Rect()
            node.getBoundsInScreen(b)
            val dm = resources.displayMetrics
            val screenWidth = dm.widthPixels.toFloat()
            val screenHeight = dm.heightPixels.toFloat()
            val trayHeight = (b.bottom - b.top).toFloat()
            if (screenWidth > 0 && screenHeight > 0 &&
                b.top > BLOCK_FEED_TRAY_TOP && b.top < BLOCK_FEED_TRAY_BOTTOM &&
                b.width() / screenWidth >= BLOCK_FEED_TRAY_WIDTH_RATIO &&
                trayHeight <= BLOCK_FEED_TRAY_MAX_HEIGHT &&
                trayHeight / screenHeight >= BLOCK_FEED_TRAY_MIN_HEIGHT_RATIO &&
                b.bottom > 0 && b.top < b.bottom) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findStoriesTray(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun resetClipsSession(reason: String) {
        val wasActive = clipsActive
        clipsActive = false
        // Keep clipsPage: the clips pager retains its position while hidden, so on the
        // next viewer appearance the blocker is armed with a known baseline (no free pass
        // for the first real swipe). -1 is only used for a genuinely unknown page.
        clipsPageConfirmed = false
        if (BuildConfig.DEBUG && wasActive) {
            Log.d(TAG, "blocker: clips session reset ($reason)")
        }
    }

    /**
     * Arms the reels blocker from the accessibility tree so it is engaged the instant the
     * full-screen reel viewer appears -- before any scroll event -- instead of relying on the
     * first scroll to arm it (which lets a fast first flick slip by).
     *
     * This NEVER disarms. Making it two-way is unsafe: transient layouts during the viewer's
     * open animation make the pager report sub-fullscreen bounds, and a false disarm re-opens
     * the free-pass window. The armed state is only ended by a non-clips feed scroll
     * (onNonClipsScroll) or by the BACK issued after a blocked swipe.
     *
     * clipsPage is only reset to -1 (baseline unknown) when the viewer is NEWLY armed from a
     * disarmed state and the Reels tab is not selected (a feed/grid/DM open, where the pager
     * reports the tapped reel's position). When the blocker is ALREADY armed, the baseline is
     * kept: repeated content-change syncs while the viewer is open must not wipe it, or a real
     * swipe can land on clipsPage == -1 and be absorbed as a "settle" -- the sporadic free
     * scroll across feed/direct/profile reels. When the Reels tab is selected the page is also
     * retained (continuity across tab reopenings).
     */
    private fun syncViewerFromTree(reason: String) {
        val root = getRootInActiveWindow() ?: return
        try {
            val reelTabSelected = isReelTabSelected(root, 0)
            val pagerFullscreen = isFullscreenClipsPager(root, 0)
            if (pagerFullscreen) {
                val wasEnabled = clipsActive
                clipsActive = true
                if (!wasEnabled) {
                    armedAt = System.currentTimeMillis()
                    if (!reelTabSelected) {
                        clipsPage = -1
                        clipsPageConfirmed = false
                    }
                }
                if (BuildConfig.DEBUG && (!wasEnabled || clipsPage == -1)) {
                    Log.d(TAG, "blocker: viewer armed via tree ($reason) tab=$reelTabSelected page=$clipsPage")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "syncViewerFromTree error", e)
        } finally {
            root.recycle()
        }
    }

    private fun isReelTabSelected(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25) return false
        val rid = node.viewIdResourceName
        if (rid != null && rid.contains("clips_tab") && node.isSelected) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isReelTabSelected(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun isFullscreenClipsPager(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25) return false
        val rid = node.viewIdResourceName
        if (rid != null && (rid.contains("clips_viewer_view_pager") ||
                rid.contains("reel_viewer") || rid.contains("clips_video_container"))) {
            val b = Rect()
            node.getBoundsInScreen(b)
            if (isNodeFullscreen(b)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isFullscreenClipsPager(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun isNodeFullscreen(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val dm = resources.displayMetrics
        val screenArea = dm.widthPixels.toFloat() * dm.heightPixels.toFloat()
        if (screenArea <= 0) return false
        val nodeArea = bounds.width().toFloat() * bounds.height().toFloat()
        return nodeArea / screenArea >= FULLSCREEN_RATIO
    }

    private fun logScrollEvent(event: AccessibilityEvent) {
        val sb = StringBuilder()
        sb.append("SCROLLED")
        sb.append(" fromIndex=${event.fromIndex}")
        sb.append(" toIndex=${event.toIndex}")
        val src = event.source
        if (src == null) {
            sb.append(" source=null")
        } else {
            val b = Rect()
            src.getBoundsInScreen(b)
            sb.append(" source cls=${src.className}")
            sb.append(" id=${src.viewIdResourceName}")
            sb.append(" scrollable=${src.isScrollable}")
            sb.append(" bounds=$b")
            src.recycle()
        }
        if (BuildConfig.DEBUG) Log.d(TAG, sb.toString())
    }

    private var lastTreeDump = 0L

    private fun dumpTree(label: String) {
        if (!BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        if (now - lastTreeDump < 3000L) return
        lastTreeDump = now
        val root = getRootInActiveWindow() ?: return
        try {
            Log.d(TAG, "--- TREE $label ---")
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.add(root to 0)
            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeLast()
                if (depth > 25) continue
                val b = Rect()
                node.getBoundsInScreen(b)
                val rid = node.viewIdResourceName
                val isInteresting = rid != null && (
                        rid.contains("clips") || rid.contains("reel") ||
                        rid.contains("story") || rid.contains("feed") ||
                        rid.contains("tab") || rid.contains("pager") ||
                        rid.contains("list") || rid.contains("is_translation") ||
                        rid.contains("name")) ||
                        node.isScrollable ||
                        node.contentDescription != null
                if (isInteresting && (node.isVisibleToUser || depth < 8)) {
                    val cn = node.className?.toString()?.substringAfterLast('.') ?: "?"
                    Log.d(TAG, "${"  ".repeat(depth)}[$cn] " +
                            "id=$rid text='${node.text}' " +
                            "sel=${node.isSelected} chk=${node.isChecked} scroll=${node.isScrollable} " +
                            "vis=${node.isVisibleToUser} bounds=$b")
                }
                var child = node.getChild(0)
                var i = 0
                while (child != null) {
                    stack.add(child to depth + 1)
                    i++
                    child = if (i < node.childCount) node.getChild(i) else null
                }
            }
            Log.d(TAG, "--- END TREE ---")
        } catch (e: Exception) {
            Log.e(TAG, "dumpTree error", e)
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(pressHomeRunnable)
        mainHandler.removeCallbacks(openFeedEvalRunnable)
        try {
            unregisterReceiver(dumpReceiver)
        } catch (_: Exception) {
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Service destroyed")
    }
}