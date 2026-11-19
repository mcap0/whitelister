package com.whitelister.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Block Feed for Facebook (com.facebook.katana): the Home feed stays at the top (composer and
 * Stories), the same rule as Instagram's Block Feed.
 *
 * Facebook strips its resource names, but the Home feed keeps `android:id/list` (no other surface
 * uses it: tabs, groups, profiles and pages were checked on 588.0.0.42.55), and the main
 * tabs are `android.app.ActionBar$Tab` nodes whose first one (leftmost) is Home -- both are
 * language-independent. When the Home feed is scrolled away from its top, the list is scrolled
 * back with ACTION_SCROLL_BACKWARD steps (programmatic: no pull-to-refresh, the feed is not
 * reloaded). If the list refuses, or the user keeps pushing against it, the Home tab is tapped
 * instead, which makes Facebook jump to the top of the feed.
 */
class FacebookFeedBlocker(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "WhitelistFB"
        private const val HOME_FEED_ID = "android:id/list"
        private const val TAB_CLASS = "android.app.ActionBar\$Tab"

        private const val THROTTLE_MS = 100L
        /** See WhitelistAccessibilityService.SNAP_STEP_MS: short steps keep the scroll fast. */
        private const val STEP_MS = 40L
        private const val MAX_STEPS = 75
        private const val STUCK_STEPS = 3
        /** The user insists: this long without reaching the top, or this many pushes down. */
        private const val INSIST_MS = 1200L
        private const val INSIST_PUSHES = 3
        /** Home-tab taps may reload the feed: never tap again within this window. */
        private const val TAB_TAP_MIN_INTERVAL_MS = 2000L
        /** After Facebook comes to the front, check whether the feed was restored mid-way. */
        private const val OPEN_SETTLE_MS = 700L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastActAt = 0L
    private var lastTabTapAt = 0L
    // Home tab selection the last time the tab bar was on screen; null = never seen (the
    // service started while the feed was scrolled down): then the Home feed list is trusted,
    // since no other Facebook surface uses android:id/list.
    private var homeSelectedWhenLastSeen: Boolean? = null

    // Snap state: the feed list being scrolled back, null when idle.
    private var target: AccessibilityNodeInfo? = null
    private var startedAt = 0L
    private var steps = 0
    private var moves = 0
    private var stuck = 0
    private var pushes = 0
    private var lastSignature: String? = null
    private val stepRunnable = Runnable { step() }
    private val openEvalRunnable = Runnable { evaluateOnOpen() }

    fun onEvent(event: AccessibilityEvent) {
        if (!PreferencesManager.isEnabled(service, IgSurface.FB_FEED)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScroll(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handler.removeCallbacks(openEvalRunnable)
                handler.postDelayed(openEvalRunnable, OPEN_SETTLE_MS)
            }
        }
    }

    /** Usage meter: true while the Home feed is the surface on screen. */
    fun isHomeFeedShown(root: AccessibilityNodeInfo): Boolean =
        isHomeTabSelected(root) && findHomeFeed(root) != null

    private fun onScroll(event: AccessibilityEvent) {
        val src = event.source ?: return
        if (src.viewIdResourceName != HOME_FEED_ID) return
        val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        if (target != null) {
            // Already scrolling back: the user is fighting it.
            kick(dy)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastActAt < THROTTLE_MS) return
        if (!isFeedList(src) || !canScrollBackward(src)) return
        val root = service.rootInActiveWindow ?: return
        if (root.packageName?.toString() != FacebookReelsBlocker.PACKAGE) return
        if (!isHomeTabSelected(root)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "FeedBlock: not on Home tab, skip")
            return
        }
        if (!PreferencesManager.shouldBlock(service, IgSurface.FB_FEED)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "FeedBlock: free time left, not blocking")
            return
        }
        lastActAt = now
        if (BuildConfig.DEBUG) Log.d(TAG, "FeedBlock: feed scrolled (dy=$dy) -> scrolling back to top")
        start(src)
    }

    /** Facebook restored the Home feed scrolled down (no scroll event fires for that). */
    private fun evaluateOnOpen() {
        if (target != null) return
        if (!PreferencesManager.shouldBlock(service, IgSurface.FB_FEED)) return
        val root = service.rootInActiveWindow ?: return
        if (root.packageName?.toString() != FacebookReelsBlocker.PACKAGE) return
        if (!isHomeTabSelected(root)) return
        val feed = findHomeFeed(root) ?: return
        if (!canScrollBackward(feed)) return
        lastActAt = SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG) Log.d(TAG, "FeedBlock: opened mid-feed -> scrolling back to top")
        start(feed)
    }

    private fun start(node: AccessibilityNodeInfo) {
        target = node
        startedAt = SystemClock.uptimeMillis()
        steps = 0
        moves = 0
        stuck = 0
        pushes = 0
        lastSignature = null
        handler.removeCallbacks(stepRunnable)
        step()
    }

    private fun kick(dy: Int) {
        steps = 0
        stuck = 0
        if (dy > 0) pushes++
        val fighting = SystemClock.uptimeMillis() - startedAt
        if ((pushes >= INSIST_PUSHES || fighting >= INSIST_MS) && tapHomeTab()) {
            // The tab bar is hidden while the feed is scrolled down: then keep scrolling back.
            finish("user insists (pushes=$pushes, ${fighting}ms), tapped Home")
        }
    }

    private fun step() {
        val node = target ?: return
        val moved = node.refresh() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (!moved) {
            val atTop = node.refresh() && !canScrollBackward(node)
            finish("list stopped after $moves step(s), atTop=$atTop")
            if (!atTop) tapHomeTab()
            return
        }
        steps++
        moves++
        // performAction can report success while the list does not move: compare the laid-out
        // children between steps and give up early so the Home-tab tap runs promptly.
        val sig = childSignature(node)
        stuck = if (sig != null && sig == lastSignature) stuck + 1 else 0
        lastSignature = sig
        if (stuck >= STUCK_STEPS || steps >= MAX_STEPS) {
            val atTop = !canScrollBackward(node)
            finish("stopped after $moves step(s), stuck=$stuck atTop=$atTop")
            if (!atTop) tapHomeTab()
            return
        }
        handler.postDelayed(stepRunnable, STEP_MS)
    }

    private fun finish(why: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "FeedBlock: snap done -- $why")
        handler.removeCallbacks(stepRunnable)
        target = null
    }

    /**
     * Re-tapping the selected Home tab makes Facebook jump to the top of the feed. Returns false
     * when no tap was made (guard window, or the tab bar is hidden).
     */
    private fun tapHomeTab(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTabTapAt < TAB_TAP_MIN_INTERVAL_MS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "FeedBlock: Home tap guard, skip")
            return false
        }
        val root = service.rootInActiveWindow ?: return false
        val home = findHomeTab(root)
        if (home == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "FeedBlock: Home tab not shown, no tap")
            return false
        }
        lastTabTapAt = now
        lastActAt = now
        val clicked = home.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (BuildConfig.DEBUG) Log.d(TAG, "FeedBlock: tapped Home tab -> jump to top clicked=$clicked")
        return clicked
    }

    private fun childSignature(node: AccessibilityNodeInfo): String? {
        if (!node.refresh()) return null
        val sb = StringBuilder()
        val b = Rect()
        for (i in 0 until minOf(node.childCount, 3)) {
            val child = node.getChild(i) ?: continue
            child.getBoundsInScreen(b)
            sb.append(b.top).append(',').append(b.bottom).append(';')
        }
        return sb.toString()
    }

    private fun canScrollBackward(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }

    /** The visible, screen-sized Home feed list. */
    private fun isFeedList(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val b = Rect()
        node.getBoundsInScreen(b)
        val dm = service.resources.displayMetrics
        return b.width() >= dm.widthPixels * 0.8f && b.height() >= dm.heightPixels * 0.4f
    }

    private fun findHomeFeed(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(HOME_FEED_ID)?.firstOrNull { isFeedList(it) }

    /**
     * Home is the first (leftmost) main tab; it must be the selected one. Facebook hides the tab
     * bar (removes it from the tree) while the feed is scrolled down, so the last state seen
     * while the bar was shown is used then.
     */
    private fun isHomeTabSelected(root: AccessibilityNodeInfo): Boolean {
        val home = findHomeTab(root) ?: return homeSelectedWhenLastSeen ?: true
        homeSelectedWhenLastSeen = home.isSelected
        return home.isSelected
    }

    private fun findHomeTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val tabs = ArrayList<AccessibilityNodeInfo>()
        collectTabs(root, 0, tabs)
        if (tabs.size < 2) return null
        val b = Rect()
        return tabs.minByOrNull { it.getBoundsInScreen(b); b.left }
    }

    private fun collectTabs(node: AccessibilityNodeInfo, depth: Int, out: MutableList<AccessibilityNodeInfo>) {
        if (depth > 12) return
        if (node.className?.toString() == TAB_CLASS) {
            if (node.isVisibleToUser) out.add(node)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTabs(child, depth + 1, out)
        }
    }
}
