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

        /**
         * After a BACK, re-read the tree this long later: either the viewer is gone (recorded, so
         * the next open is treated as a fresh open with its settle window) or IG swallowed the
         * BACK and the viewer is still up (re-armed strictly, so the next swipe blocks again).
         */
        private const val BACK_VERIFY_DELAY_MS = 600L

        /**
         * Once armed this long, a tree read that finds no full-screen viewer means the viewer
         * really left (the open animation's transient sub-fullscreen bounds are over), so the
         * session is reset. Without this the blocker stayed armed after the viewer closed, and
         * the NEXT reel opened without scrolling the feed in between inherited the stale state
         * and was closed on open.
         */
        private const val VIEWER_LEAVE_GRACE_MS = 1000L

        /** Usage meter: how often the foreground surface is sampled, and the max credit per sample. */
        private const val USAGE_TICK_MS = 1000L
        private const val USAGE_TICK_MAX_MS = 2000L
        /** Usage is written to SharedPreferences at most this often (and when IG leaves the screen). */
        private const val USAGE_FLUSH_MS = 5000L

        /**
         * Scroll-to-top without reload: the feed/explore list is scrolled back with
         * ACTION_SCROLL_BACKWARD (one viewport per step) instead of re-tapping the Home tab, which
         * pull-to-refreshes. Steps repeat until the list reports it is at the top.
         */
        private const val SNAP_DELAY_MS = 150L
        private const val SNAP_STEP_MS = 120L
        private const val SNAP_MAX_STEPS = 25
        /** While a snap is running (and this long after its last step) its own scroll events are ignored. */
        private const val SNAP_BUSY_MS = 450L

        /** Throttle for Block Explore's scroll handling. */
        private const val BLOCK_EXPLORE_THROTTLE_MS = 300L

        private const val SEARCH_TAB_ID = "com.instagram.android:id/search_tab"
        private const val FEED_LIST_ALT_ID = "com.instagram.android:id/swipeable_nav_view_pager_inner_recycler_view"
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
    // Last time a tree read found NO full-screen reel viewer. Compared with lastBackAt to tell a
    // fresh open (viewer seen gone since the last block) from a swallowed BACK (viewer never left).
    private var lastViewerAbsentAt = 0L
    private val backVerifyRunnable = Runnable {
        syncViewerFromTree("back-verify")
    }

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
    // Scrolled list captured from the triggering scroll event, used by the deferred snap.
    private var pendingFeedNode: AccessibilityNodeInfo? = null
    private val feedSnapRunnable = Runnable {
        startFeedSnap()
    }
    private val feedSnapper = TopSnapper(
        label = "BlockFeed",
        isAtTop = { feedAtTopNow() },
        onFailed = { fallbackPressHome() }
    )

    // --- Block Explore state ---
    private var lastBlockExploreAt = 0L
    private val exploreSnapper = TopSnapper(
        label = "BlockExplore",
        isAtTop = null,
        onFailed = null
    )

    // --- Usage meter (daily free time per surface) ---
    private var usageTickerRunning = false
    private var lastUsageTickAt = 0L
    private var lastUsageFlushAt = 0L
    private val pendingUsageMs = HashMap<IgSurface, Long>()
    private val usageTicker = Runnable {
        usageTick()
    }

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
        ensureUsageTicker()

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
            onNonClipsScroll(src, rid)
        }
    }

    private fun onClipsPagerScroll(event: AccessibilityEvent) {
        // Anchor on toIndex: it is the page the pager RESTED at after this motion, while
        // fromIndex is the page left behind. maxOf(from,to) makes a revert swipe (2->1) look
        // like the current page and grants a free pass. Settles (from==to) are unaffected.
        // Guard toIndex == -1 (some IG events carry no index) with a maxOf fallback.
        val cur = if (event.toIndex >= 0) event.toIndex else maxOf(event.fromIndex, event.toIndex)
        val now = System.currentTimeMillis()

        if (!clipsActive && lastBackAt > 0 && now - lastBackAt < BACK_VERIFY_DELAY_MS) {
            // Event storm of the viewer we just closed with BACK. backVerifyRunnable re-arms
            // strictly if the BACK was swallowed; arming from these events would leave the
            // blocker armed (with a stale page) after the viewer is gone.
            return
        }
        if (!clipsActive) {
            // A stray non-clips scroll (feed/direct/grid inner scrollables) or a raced
            // tree sync can leave the blocker un-armed while the viewer is open. Re-arm
            // from the tree before deciding: never blanket-absorb, or a fast first swipe
            // gets a free pass.
            syncViewerFromTree("resume")
            if (clipsActive) {
                // syncViewerFromTree already decided the window: a fresh open (viewer seen gone
                // since the last block) keeps its open-settle window, because THIS event is most
                // likely IG's opening reposition -- the content-change sync is throttled, so a
                // reel opened right after a blocked one is often armed by its first scroll
                // event. Zeroing armedAt here made that reposition read as a swipe and closed
                // the reel on open. A swallowed BACK / stray disarm is armed strictly instead.
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: re-armed via tree (page=$clipsPage, armedAt=$armedAt)")
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
            if (PreferencesManager.shouldBlock(this, IgSurface.REELS)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: SWIPE (no baseline) ${event.fromIndex}->$cur, issuing BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastBackAt = now
                clipsActive = false
                clipsPage = cur
                clipsPageConfirmed = false
                scheduleBackVerify()
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
        if (PreferencesManager.shouldBlock(this, IgSurface.REELS)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: SWIPE $clipsPage->$cur, issuing BACK")
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBackAt = now
            clipsActive = false
            clipsPage = cur
            clipsPageConfirmed = false
            scheduleBackVerify()
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
            val open = isFullscreenClipsPager(root, 0)
            if (!open) lastViewerAbsentAt = now
            return open
        } finally {
            root.recycle()
        }
    }

    private fun onNonClipsScroll(src: AccessibilityNodeInfo, rid: String?) {
        // Block Feed: any non-clips scroll while the home feed can be engaged should send us
        // back to the top. This runs even if reels blocking's clips session is not active.
        if (PreferencesManager.shouldBlock(this, IgSurface.FEED)) {
            blockFeedOnScroll(src)
        }
        if (PreferencesManager.shouldBlock(this, IgSurface.EXPLORE)) {
            blockExploreOnScroll(src)
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
     * Block Feed: when the user scrolls the Home feed away from the top, scroll it straight back.
     *
     * The feed list itself is scrolled with ACTION_SCROLL_BACKWARD (TopSnapper), which moves it
     * programmatically: no pull-to-refresh, so the feed is NOT reloaded. Only when the list
     * refuses the action (IG build without accessibility scrolling) does it fall back to the old
     * mechanism, re-tapping the bottom-nav Home tab, which snaps to top but also refreshes.
     *
     * The at-top test is the Stories tray: expanded (>10% screen height) it exists only at the
     * very top of the feed. While the tray is NOT expanded we are scrolled down and may act.
     * After a Home-tap fallback the reload hides the tray, so a re-arm gate
     * (sawTopSinceBlockPress) prevents re-pressing until the feed is seen back at top.
     */
    private fun blockFeedOnScroll(src: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastBlockFeedAt < BLOCK_FEED_THROTTLE_MS) return
        // Our own scroll-back emits scroll events; let the running snap finish.
        if (feedSnapper.isBusy()) return
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
        pendingFeedNode = if (isVerticalList(src)) src else null
        // Act AFTER the current scroll settles: injecting mid-motion gets merged into the user's
        // ongoing drag and swallowed. The delay lets IG reach its resting position first.
        mainHandler.removeCallbacks(feedSnapRunnable)
        mainHandler.postDelayed(feedSnapRunnable, BLOCK_FEED_PRESS_DELAY_MS)
    }

    /** Scrolls the feed back to the top without reloading it (falls back to the Home tap). */
    private fun startFeedSnap() {
        val node = pendingFeedNode ?: findFeedListNode()
        pendingFeedNode = null
        if (node == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: feed list not found, Home-tap fallback")
            fallbackPressHome()
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: scrolling feed back to top (no reload) id=${node.viewIdResourceName}")
        feedSnapper.start(node)
    }

    private fun fallbackPressHome() {
        val now = System.currentTimeMillis()
        lastBlockFeedAt = now
        lastBlockFeedPressAt = now
        sawTopSinceBlockPress = false
        mainHandler.removeCallbacks(pressHomeRunnable)
        mainHandler.post(pressHomeRunnable)
    }

    private fun feedAtTopNow(): Boolean {
        val root = getRootInActiveWindow() ?: return false
        try {
            return findStoriesTray(root, 0)
        } finally {
            root.recycle()
        }
    }

    /** The visible Home feed list (android:id/list or IG's swipeable nav inner recycler). */
    private fun findFeedListNode(): AccessibilityNodeInfo? {
        val root = getRootInActiveWindow() ?: return null
        for (id in listOf(FEED_LIST_ID, FEED_LIST_ALT_ID)) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            val match = nodes.firstOrNull { it.isVisibleToUser && it.isScrollable && isVerticalList(it) }
            if (match != null) return match
        }
        return null
    }

    /** A tall, wide scrollable: a vertical list or grid (not a horizontal tray/carousel). */
    private fun isVerticalList(node: AccessibilityNodeInfo): Boolean {
        if (!node.isScrollable) return false
        val b = Rect()
        node.getBoundsInScreen(b)
        val dm = resources.displayMetrics
        return b.height() >= dm.heightPixels * 0.4f && b.width() >= dm.widthPixels * 0.8f
    }

    /**
     * Block Explore: on the Search/Explore tab the recommendations grid (and the post feed it
     * opens) cannot be scrolled: any scroll away from the top is scrolled straight back, so only
     * the first row of suggestions is visible. Searching stays free: while the search field is
     * in use (focused, or holding a query) the results list scrolls normally, and profiles
     * opened from search are left alone.
     */
    private fun blockExploreOnScroll(src: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastBlockExploreAt < BLOCK_EXPLORE_THROTTLE_MS) return
        if (exploreSnapper.isBusy()) return
        if (!isVerticalList(src) || !canScrollBackward(src)) return
        if (isClipsViewerOpen()) return
        val root = getRootInActiveWindow() ?: return
        try {
            if (!isExploreSurface(root)) return
        } finally {
            root.recycle()
        }
        lastBlockExploreAt = now
        if (BuildConfig.DEBUG) Log.d(TAG, "BlockExplore: scrolling explore back to top id=${src.viewIdResourceName}")
        exploreSnapper.start(src, BLOCK_FEED_PRESS_DELAY_MS)
    }

    private fun canScrollBackward(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }

    /**
     * True on the Explore recommendations surface: the Search tab is selected, the search field
     * is not being used, and the explore grid (not a profile/hashtag/place page) is showing.
     */
    private fun isExploreSurface(root: AccessibilityNodeInfo): Boolean {
        if (!isBottomTabSelected(root, SEARCH_TAB_ID)) return false
        if (isSearchInUse(root, 0)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "BlockExplore: search in use, skip")
            return false
        }
        if (isProfileLikePage(root, 0)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "BlockExplore: profile/page opened from search, skip")
            return false
        }
        return true
    }

    private fun isBottomTabSelected(root: AccessibilityNodeInfo, tabId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(tabId) ?: return false
        return nodes.any { it.isSelected || it.isChecked }
    }

    /** The search EditText is focused or holds a typed query (not just its hint). */
    private fun isSearchInUse(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25) return false
        if (node.isEditable && node.isVisibleToUser) {
            if (node.isFocused) return true
            val hasQuery = !node.text.isNullOrEmpty() &&
                    !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText)
            if (hasQuery) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isSearchInUse(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    /** A profile, hashtag or place page pushed on top of the Search tab. */
    private fun isProfileLikePage(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25) return false
        val rid = node.viewIdResourceName
        if (rid != null && node.isVisibleToUser && (
                    rid.contains("profile_header") || rid.contains("row_profile_header") ||
                    rid.contains("hashtag_header") || rid.contains("location_header"))) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isProfileLikePage(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    /**
     * Block Feed, on app open: if the Home feed appeared scrolled down (IG restores the previous
     * session's scroll) snap it back to the top -- no scroll event fires for a restored position,
     * so the scroll-driven path alone would leave it mid-feed. Also re-arms the loop-guard state
     * for a fresh session so a stale mid-refresh flag from the last session can't suppress the
     * first bounce. Never acts while the feed is rendered at top.
     */
    private fun evaluateBlockFeedOnOpen() {
        if (!PreferencesManager.shouldBlock(this, IgSurface.FEED)) return
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
                // between open-snaps (a Home <-> surface <-> Home cycle must not double-act).
                if (now - lastBlockFeedPressAt < BLOCK_FEED_OPEN_MIN_INTERVAL_MS) return
                if (now - lastBlockFeedAt < BLOCK_FEED_THROTTLE_MS) return
                if (feedSnapper.isBusy()) return
                lastBlockFeedAt = now
                sawTopSinceBlockPress = true
                if (BuildConfig.DEBUG) Log.d(TAG, "BlockFeed: opened mid-feed -> snap to top")
                pendingFeedNode = null
                mainHandler.removeCallbacks(feedSnapRunnable)
                mainHandler.postDelayed(feedSnapRunnable, BLOCK_FEED_PRESS_DELAY_MS)
            }
            // Not Home (another tab genuinely selected): leave the restored surface alone.
        } finally {
            root.recycle()
        }
    }

    /**
     * Scrolls one list back to its top with repeated ACTION_SCROLL_BACKWARD steps (one viewport
     * each). This is a programmatic scroll, so unlike re-tapping the Home tab it never triggers
     * pull-to-refresh. Stops when the list refuses to scroll further (at top), when [isAtTop]
     * says so, or after SNAP_MAX_STEPS. [onFailed] runs when the list never moved at all, or
     * ran out of steps without reaching the top.
     */
    private inner class TopSnapper(
        private val label: String,
        private val isAtTop: (() -> Boolean)?,
        private val onFailed: (() -> Unit)?
    ) {
        private var target: AccessibilityNodeInfo? = null
        private var steps = 0
        private var stuckSteps = 0
        private var lastSignature: String? = null
        private var busyUntil = 0L
        private val stepRunnable = Runnable { step() }

        fun isBusy(): Boolean = System.currentTimeMillis() < busyUntil

        fun start(node: AccessibilityNodeInfo, delayMs: Long = 0L) {
            target = node
            steps = 0
            stuckSteps = 0
            lastSignature = null
            busyUntil = System.currentTimeMillis() + delayMs + SNAP_BUSY_MS
            mainHandler.removeCallbacks(stepRunnable)
            mainHandler.postDelayed(stepRunnable, delayMs)
        }

        fun cancel() {
            mainHandler.removeCallbacks(stepRunnable)
            target = null
            busyUntil = 0L
        }

        private fun step() {
            val node = target ?: return
            if (steps > 0 && isAtTop?.invoke() == true) {
                finish("at top after $steps step(s)")
                return
            }
            val moved = node.refresh() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (!moved) {
                if (steps == 0 && isAtTop?.invoke() != true) {
                    finish("list refused to scroll")
                    onFailed?.invoke()
                } else {
                    finish("reached top after $steps step(s)")
                }
                return
            }
            steps++
            busyUntil = System.currentTimeMillis() + SNAP_BUSY_MS
            // performAction can report success while the list does not move (IG intercepting the
            // scroll). Compare the laid-out children between steps; if nothing moves for a few
            // steps, give up early so the fallback runs promptly instead of after ~3s.
            val sig = childSignature(node)
            stuckSteps = if (sig != null && sig == lastSignature) stuckSteps + 1 else 0
            lastSignature = sig
            if (stuckSteps >= 2) {
                val atTop = isAtTop?.invoke() ?: !canScrollBackward(node)
                finish("list not moving (atTop=$atTop)")
                if (!atTop) onFailed?.invoke()
                return
            }
            if (steps >= SNAP_MAX_STEPS) {
                val atTop = isAtTop?.invoke() ?: !canScrollBackward(node)
                finish("gave up after $steps steps (atTop=$atTop)")
                if (!atTop) onFailed?.invoke()
                return
            }
            mainHandler.postDelayed(stepRunnable, SNAP_STEP_MS)
        }

        /** Position fingerprint of the list: its first few children's bounds. */
        private fun childSignature(node: AccessibilityNodeInfo): String? {
            if (!node.refresh()) return null
            val sb = StringBuilder()
            val b = Rect()
            for (i in 0 until minOf(node.childCount, 3)) {
                val child = node.getChild(i) ?: continue
                child.getBoundsInScreen(b)
                sb.append(b.top).append(',').append(b.bottom).append(';')
                child.recycle()
            }
            return sb.toString()
        }

        private fun finish(why: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "$label: snap done -- $why")
            target = null
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

    private fun scheduleBackVerify() {
        mainHandler.removeCallbacks(backVerifyRunnable)
        mainHandler.postDelayed(backVerifyRunnable, BACK_VERIFY_DELAY_MS)
    }

    // --- Usage meter -------------------------------------------------------------------------
    // Daily free time ("block only after N minutes") needs to know how long the user spends on
    // each surface. While Instagram is in the foreground the active window is sampled once per
    // second and the elapsed time is credited to the surface on screen. The ticker stops by
    // itself as soon as another app (launcher, lock screen) is in front, and restarts on the
    // next Instagram event.

    private fun ensureUsageTicker() {
        if (usageTickerRunning) return
        usageTickerRunning = true
        lastUsageTickAt = System.currentTimeMillis()
        mainHandler.postDelayed(usageTicker, USAGE_TICK_MS)
    }

    private fun usageTick() {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastUsageTickAt).coerceIn(0L, USAGE_TICK_MAX_MS)
        lastUsageTickAt = now
        val root = getRootInActiveWindow()
        if (root != null && root.packageName?.toString() != "com.instagram.android") {
            root.recycle()
            usageTickerRunning = false
            flushUsage()
            return
        }
        if (root != null) {
            try {
                val surface = currentSurface(root)
                if (surface != null) {
                    pendingUsageMs[surface] = (pendingUsageMs[surface] ?: 0L) + elapsed
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "usageTick error", e)
            } finally {
                root.recycle()
            }
        }
        if (now - lastUsageFlushAt >= USAGE_FLUSH_MS) flushUsage()
        mainHandler.postDelayed(usageTicker, USAGE_TICK_MS)
    }

    private fun flushUsage() {
        lastUsageFlushAt = System.currentTimeMillis()
        if (pendingUsageMs.isEmpty()) return
        for ((surface, ms) in pendingUsageMs) {
            PreferencesManager.addUsedMs(this, surface, ms)
        }
        pendingUsageMs.clear()
    }

    /** Which metered surface is on screen: a full-screen reel, the Home feed or Explore. */
    private fun currentSurface(root: AccessibilityNodeInfo): IgSurface? {
        if (isFullscreenClipsPager(root, 0)) return IgSurface.REELS
        if (isBottomTabSelected(root, SEARCH_TAB_ID)) {
            return if (!isSearchInUse(root, 0) && !isProfileLikePage(root, 0)) IgSurface.EXPLORE else null
        }
        if (isOnHomeTabGivenSelection(root) && isHomeFeedRendered(root)) return IgSurface.FEED
        return null
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
            val now = System.currentTimeMillis()
            if (pagerFullscreen && !clipsActive && lastBackAt > 0 &&
                now - lastBackAt < BACK_VERIFY_DELAY_MS && reason != "back-verify") {
                // The viewer we just blocked is still animating out. Arming now would leave the
                // blocker armed after it disappears; backVerifyRunnable decides once it settles.
                return
            }
            if (pagerFullscreen) {
                val wasEnabled = clipsActive
                clipsActive = true
                if (!wasEnabled) {
                    if (lastBackAt > 0 && lastViewerAbsentAt < lastBackAt) {
                        // The viewer never left since our last BACK: IG swallowed it. This is
                        // the same viewer, still at the page the blocked swipe reached -- keep
                        // that baseline and grant no settle window, so the next swipe blocks.
                        armedAt = 0
                        if (BuildConfig.DEBUG) Log.d(TAG, "blocker: BACK swallowed, re-armed strictly ($reason) page=$clipsPage")
                    } else {
                        armedAt = now
                        if (!reelTabSelected) {
                            clipsPage = -1
                            clipsPageConfirmed = false
                        }
                    }
                }
                if (BuildConfig.DEBUG && (!wasEnabled || clipsPage == -1)) {
                    Log.d(TAG, "blocker: viewer armed via tree ($reason) tab=$reelTabSelected page=$clipsPage")
                }
            } else {
                lastViewerAbsentAt = now
                if (clipsActive && now - armedAt > VIEWER_LEAVE_GRACE_MS) {
                    resetClipsSession("viewer left ($reason)")
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
        mainHandler.removeCallbacks(feedSnapRunnable)
        mainHandler.removeCallbacks(backVerifyRunnable)
        mainHandler.removeCallbacks(usageTicker)
        feedSnapper.cancel()
        exploreSnapper.cancel()
        flushUsage()
        try {
            unregisterReceiver(dumpReceiver)
        } catch (_: Exception) {
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Service destroyed")
    }
}