package com.whitelister.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Remove Reels for Facebook (com.facebook.katana): watch the reel you opened, not the next one.
 *
 * Facebook strips its resource names (every id reads "(name removed)") and does not expose the
 * adapter position of list items, so the Instagram approach (matching the clips pager by id) is
 * not available. The reel pager is recognised by its shape instead, language-independently:
 *  - a vertical RecyclerView covering most of the screen that is not the Home feed
 *    (android:id/list, which also embeds inline reels);
 *  - whose visible item fills the pager and carries the reel ACTION RAIL: like / comment / share /
 *    save stacked on the right edge of the screen. Video, photo and sponsored reels all have it;
 *    ordinary feeds do not (their action row is horizontal).
 * Every full-screen reel surface is such a pager: the Reels tab, and the immersive viewer
 * (ImmersiveActivity) that plays reels and videos opened from the feed, profiles, search...
 *
 * The incoming reel's content is not in the accessibility tree yet while a swipe is in progress,
 * so the pager is recognised while at rest (throttled tree scans on content changes, which
 * Facebook emits continuously while a reel plays) and remembered by node identity.
 *
 * Swipe detection uses the scroll events' visible range (fromIndex..toIndex). At rest a pager
 * shows one reel (fromIndex == toIndex, or a 1px header row above the first reel), so a scroll
 * that shows two items with real movement is a page change in progress -> BACK, the same
 * reaction as on Instagram (closes the immersive viewer / leaves the Reels tab for Home). A swipe
 * whose intermediate events were all missed is still caught when it settles on another reel.
 */
class FacebookReelsBlocker(private val service: AccessibilityService) {

    companion object {
        const val PACKAGE = "com.facebook.katana"
        private const val TAG = "WhitelistFB"
        private const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"
        private const val HOME_FEED_ID = "android:id/list"

        /** Ignore the pager this long after a BACK: the closing viewer keeps emitting scrolls. */
        private const val BACK_COOLDOWN_MS = 1500L

        /** Throttle for the tree scans that recognise the pager before the first swipe. */
        private const val SCAN_THROTTLE_MS = 500L

        /**
         * A scroll showing two items must move at least this far (px) to count as a swipe. Real
         * swipes move tens to hundreds of px per event; layout passes (opening the viewer) report
         * 0 and the 1px header row above the first reel never gets near this.
         */
        private const val MIN_SWIPE_DY = 10

        /** The pager covers at least this share of the screen area. */
        private const val FULLSCREEN_RATIO = 0.6f

        /** At rest the visible reel fills (almost) the whole pager height. */
        private const val PAGE_FILL_RATIO = 0.85f

        /** Action-rail buttons start in this right-hand part of the screen... */
        private const val RAIL_LEFT_RATIO = 0.85f
        /** ...at least this many of them, stacked vertically over at least this screen share. */
        private const val RAIL_MIN_BUTTONS = 3
        private const val RAIL_MIN_SPREAD_RATIO = 0.08f

        private const val MAX_DEPTH = 30
        private const val MAX_KNOWN_PAGERS = 4
    }

    // Reel pagers recognised at rest (Reels tab, immersive viewers), matched by node identity
    // (AccessibilityNodeInfo.equals compares window and view ids).
    private val knownPagers = ArrayList<AccessibilityNodeInfo>()
    // The pager the baseline below belongs to.
    private var current: AccessibilityNodeInfo? = null
    // Resting reel index of [current]; -1 = unknown (fresh pager, or reset after a BACK).
    private var baseline = -1
    private var lastBackAt = 0L
    private var lastScanAt = 0L

    fun onEvent(event: AccessibilityEvent) {
        if (!PreferencesManager.isEnabled(service, IgSurface.FB_REELS)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "WINDOW_STATE cls=${event.className}")
                scan("window")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (SystemClock.uptimeMillis() - lastScanAt >= SCAN_THROTTLE_MS) scan("content")
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScroll(event)
        }
    }

    /** Usage meter: which surface of Facebook is on screen (only reels are metered). */
    fun currentSurface(root: AccessibilityNodeInfo): IgSurface? =
        if (findReelPager(root, 0) != null) IgSurface.FB_REELS else null

    private fun onScroll(event: AccessibilityEvent) {
        if (event.className?.toString() != RECYCLER_VIEW) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < BACK_COOLDOWN_MS) return
        val src = event.source ?: return
        if (!src.isVisibleToUser) return
        if (knownPagers.none { it == src }) {
            // Not seen at rest yet (opened and swiped within one scan throttle): recognise it from
            // the reel still on screen, if enough of it is left to show the rail.
            if (!isReelPager(src)) return
            remember(src, "scroll")
        }
        if (current != src) {
            current = src
            baseline = -1
        }
        val from = event.fromIndex
        val to = event.toIndex
        val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else MIN_SWIPE_DY
        if (BuildConfig.DEBUG) Log.d(TAG, "reel scroll from=$from to=$to dy=$dy baseline=$baseline")
        if (from < 0 || to < 0) return

        if (from != to) {
            if (abs(dy) < MIN_SWIPE_DY) {
                // Layout pass (viewer opening, header row above the first reel): no movement.
                if (baseline < 0) baseline = to
                return
            }
            // Two reels on screen and moving: a swipe (or auto-advance) to another reel.
            block("swipe $from..$to dy=$dy")
            return
        }
        // At rest on one reel. Landing on a different reel than the one being watched -- with
        // real movement, so data reloads that renumber the list (dy == 0) are not mistaken for
        // one -- is a swipe whose intermediate events were all missed.
        if (baseline >= 0 && to != baseline && dy != 0) {
            if (block("settled on reel $to, was $baseline")) return
        }
        baseline = to
    }

    /** BACK out of the reel surface. Returns false when today's free time still allows scrolling. */
    private fun block(why: String): Boolean {
        if (!PreferencesManager.shouldBlock(service, IgSurface.FB_REELS)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "SWIPE ($why), free time left, not blocking")
            return false
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "SWIPE ($why) -> BACK")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        lastBackAt = SystemClock.uptimeMillis()
        // The pager keeps animating to whichever reel it snaps to; the next session starts fresh
        // so that reel is taken as the new baseline instead of reading as another swipe.
        current = null
        baseline = -1
        return true
    }

    /** Recognises the reel pager on screen ahead of the first swipe. */
    private fun scan(reason: String) {
        lastScanAt = SystemClock.uptimeMillis()
        val root = service.rootInActiveWindow ?: return
        if (root.packageName?.toString() != PACKAGE) return
        val pager = findReelPager(root, 0) ?: return
        remember(pager, reason)
    }

    private fun remember(pager: AccessibilityNodeInfo, reason: String) {
        if (knownPagers.any { it == pager }) return
        knownPagers.add(pager)
        if (knownPagers.size > MAX_KNOWN_PAGERS) knownPagers.removeAt(0)
        if (BuildConfig.DEBUG) Log.d(TAG, "reel pager recognised ($reason)")
    }

    /**
     * Depth-first search for a visible reel pager. Never descends into other RecyclerViews: a reel
     * pager is not nested inside a list, and skipping them keeps the Home feed's subtree (large)
     * out of every scan. Hidden subtrees (other tabs) are skipped too.
     */
    private fun findReelPager(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || !node.isVisibleToUser) return null
        if (node.className?.toString() == RECYCLER_VIEW) {
            return if (isReelPager(node)) node else null
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findReelPager(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** A full-screen vertical list whose visible item fills it and carries the reel action rail. */
    private fun isReelPager(list: AccessibilityNodeInfo): Boolean {
        if (list.className?.toString() != RECYCLER_VIEW || !list.isVisibleToUser) return false
        if (list.viewIdResourceName == HOME_FEED_ID) return false
        val lb = Rect()
        list.getBoundsInScreen(lb)
        if (!isFullscreen(lb)) return false
        val ib = Rect()
        for (i in 0 until list.childCount) {
            val item = list.getChild(i) ?: continue
            item.getBoundsInScreen(ib)
            if (ib.height() >= lb.height() * PAGE_FILL_RATIO &&
                ib.width() >= lb.width() * 0.9f &&
                hasActionRail(item)) {
                return true
            }
        }
        return false
    }

    /** At least [RAIL_MIN_BUTTONS] small clickables stacked along the right edge of the screen. */
    private fun hasActionRail(item: AccessibilityNodeInfo): Boolean {
        val dm = service.resources.displayMetrics
        val tops = HashSet<Int>()
        collectRailTops(item, 0, dm.widthPixels, dm.heightPixels, tops)
        if (tops.size < RAIL_MIN_BUTTONS) return false
        return (tops.max() - tops.min()) >= dm.heightPixels * RAIL_MIN_SPREAD_RATIO
    }

    private fun collectRailTops(node: AccessibilityNodeInfo, depth: Int, w: Int, h: Int, out: HashSet<Int>) {
        if (depth > 16 || !node.isVisibleToUser) return
        if (node.isClickable) {
            val b = Rect()
            node.getBoundsInScreen(b)
            if (b.left >= w * RAIL_LEFT_RATIO && b.width() in 1..(w / 5) && b.height() in 1..(h / 6)) {
                out.add(b.top)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectRailTops(child, depth + 1, w, h, out)
        }
    }

    private fun isFullscreen(b: Rect): Boolean {
        if (b.isEmpty) return false
        val dm = service.resources.displayMetrics
        val screen = dm.widthPixels.toFloat() * dm.heightPixels.toFloat()
        if (screen <= 0f) return false
        return b.width() >= dm.widthPixels * 0.9f &&
                b.width().toFloat() * b.height().toFloat() / screen >= FULLSCREEN_RATIO
    }

    /** Debug: full dump of the active window when it belongs to Facebook (adb broadcast DUMP). */
    fun dump(label: String) {
        if (!BuildConfig.DEBUG) return
        val root = service.rootInActiveWindow ?: return
        if (root.packageName?.toString() != PACKAGE) return
        Log.d(TAG, "--- FB TREE $label ---")
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        val b = Rect()
        var count = 0
        while (stack.isNotEmpty() && count < 1500) {
            val (node, depth) = stack.removeLast()
            count++
            node.getBoundsInScreen(b)
            val cn = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val flags = buildString {
                if (node.isScrollable) append("S")
                if (node.isSelected) append("s")
                if (node.isClickable) append("c")
                if (!node.isVisibleToUser) append("H")
            }
            val rid = node.viewIdResourceName?.substringAfter(":id/")
            val cd = node.contentDescription?.toString()?.take(60)
            val tx = node.text?.toString()?.take(40)
            Log.d(TAG, "${" ".repeat(depth)}$depth[$cn]${if (rid != null) " id=$rid" else ""}" +
                    "${if (cd != null) " cd='$cd'" else ""}${if (tx != null) " t='$tx'" else ""} $flags $b")
            if (depth >= 60) continue
            for (i in node.childCount - 1 downTo 0) {
                val c = node.getChild(i) ?: continue
                stack.add(c to depth + 1)
            }
        }
        Log.d(TAG, "--- END FB TREE ($count nodes) ---")
    }
}
