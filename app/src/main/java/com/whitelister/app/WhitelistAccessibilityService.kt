package com.whitelister.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
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
        Log.d(TAG, "Service connected")
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
        Log.d(TAG, sb.toString())
    }

    private var lastTreeDump = 0L

    private fun dumpTree(label: String) {
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
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(dumpReceiver)
        } catch (_: Exception) {
        }
        Log.d(TAG, "Service destroyed")
    }
}