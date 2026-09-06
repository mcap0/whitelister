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
                resetClipsSession("app window change")
                syncViewerFromTree("window")
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
        // IG reports the pager's current reel index here and fromIndex/toIndex can be
        // either the page being left or the page just entered (reporting is unreliable
        // across flings). Use the larger of the two so a completed reel transition is
        // always detected against clipsPage, while a pure settle (fromIndex==toIndex)
        // still passes. This closes the "one free scroll" in the Reels tab without
        // weakening the feed-open path (it can only block more, never less).
        val cur = maxOf(event.fromIndex, event.toIndex)
        val now = System.currentTimeMillis()

        if (now - lastBackAt < BACK_COOLDOWN_MS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: cooldown after BACK, ignoring clips scroll")
            return
        }

        if (!clipsActive) {
            // Viewer not armed by the tree yet (e.g. first scroll raced the sync).
            // Establish the baseline before it can be compared against.
            clipsActive = true
            clipsPage = cur
            clipsPageConfirmed = false
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: reels viewer open at page $cur")
            return
        }

        if (clipsPage == -1) {
            // Viewer visible but baseline unknown: reel-tab on a cold start, or the
            // pager was repositioned by a feed-open.
            if (event.fromIndex == event.toIndex) {
                // Settle/positioning event at the reel currently shown: absorb it as
                // the baseline so a genuine page change (a swipe) is what triggers the
                // BACK below, not the viewer open itself.
                clipsPage = cur
                clipsPageConfirmed = true
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: reels viewer baseline at page $cur")
            } else {
                // A genuine swipe arrived before any settle (ultra-fast cold-start
                // flick, no settle emitted). Block it outright: there is no baseline
                // to protect, so this is the user scrolling.
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
            }
            return
        }

        if (cur == clipsPage) {
            if (!clipsPageConfirmed) {
                clipsPageConfirmed = true
                if (BuildConfig.DEBUG) Log.d(TAG, "blocker: reels viewer settled at page $cur")
            }
            return
        }

        // Page changed. No open-anim absorb: the feed can auto-advance a reel before
        // we tap it, so the "open settle" would grant a free pass. Every page move is
        // treated as a swipe to block.
        if (PreferencesManager.isReelsBlockingEnabled(this)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: SWIPE ${clipsPage}->$cur, issuing BACK")
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBackAt = now
            clipsActive = false
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: SWIPE ${clipsPage}->$cur (blocking disabled)")
            clipsPage = cur
            clipsPageConfirmed = false
        }
    }

    private fun onNonClipsScroll(rid: String?) {
        if (clipsActive) {
            if (BuildConfig.DEBUG) Log.d(TAG, "blocker: reels viewer closed (feed scroll source=$rid)")
            resetClipsSession("feed scroll")
        }
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
     * When the reel TAB is selected the pager's page is continuous (it keeps its position),
     * so clipsPage is retained. When the viewer opens from the feed or a grid, the pager was
     * repositioned and the baseline is reset to -1 (re-established from the settle on the
     * next clips scroll).
     */
    private fun syncViewerFromTree(reason: String) {
        val root = getRootInActiveWindow() ?: return
        try {
            val reelTabSelected = isReelTabSelected(root, 0)
            val pagerFullscreen = isFullscreenClipsPager(root, 0)
            if (pagerFullscreen) {
                val wasEnabled = clipsActive
                clipsActive = true
                if (!reelTabSelected) {
                    clipsPage = -1
                    clipsPageConfirmed = false
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