package com.whitelister.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhitelistAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhitelistService"
        private const val REELS_COOLDOWN_MS = 2000L
        private const val CONTENT_DETECT_THROTTLE_MS = 500L
        private const val FULLSCREEN_RATIO = 0.7f
        private const val BLOCK_HOME_THROTTLE_MS = 1000L
        private const val REELS_GRACE_MS = 1000L

        var isRunning = false
            private set

        var instance: WhitelistAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastReelsBlockTime = 0L
    private var lastContentDetectTime = 0L
    private var lastBlockHomeTime = 0L
    private var isInReelsViewer = false
    private var reelsEnteredAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this

        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        setServiceInfo(info)

        Log.d(TAG, "WhitelistAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName != "com.instagram.android") return

        val reelsEnabled = PreferencesManager.isReelsBlockingEnabled(this)
        val blockHomeEnabled = PreferencesManager.isBlockHomeFeedEnabled(this)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (reelsEnabled) {
                    detectReelsViewer()
                } else {
                    isInReelsViewer = false
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (reelsEnabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentDetectTime >= CONTENT_DETECT_THROTTLE_MS) {
                        lastContentDetectTime = now
                        detectReelsViewer()
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (reelsEnabled) {
                    if (!isInReelsViewer) {
                        val now = System.currentTimeMillis()
                        if (now - lastContentDetectTime >= CONTENT_DETECT_THROTTLE_MS) {
                            lastContentDetectTime = now
                            detectReelsViewer()
                        }
                    }
                    if (isInReelsViewer) {
                        val sinceEnter = System.currentTimeMillis() - reelsEnteredAt
                        if (sinceEnter >= REELS_GRACE_MS) {
                            blockReels()
                        } else {
                            Log.d(TAG, "Reels: within grace ($sinceEnter ms), ignore scroll")
                        }
                    }
                }
                if (blockHomeEnabled) applyBlockHomeFeed()
            }
        }
    }

    private fun detectReelsViewer() {
        val wasInViewer = isInReelsViewer
        isInReelsViewer = false

        try {
            val rootNode = getRootInActiveWindow() ?: return
            isInReelsViewer = isReelsTabSelected(rootNode, 0) ||
                    isFullscreenReelViewer(rootNode, 0)
            rootNode.recycle()
            // On entering the viewer, arm the grace period so the scroll event
            // emitted by simply opening a reel doesn't immediately back out.
            if (isInReelsViewer && !wasInViewer) {
                reelsEnteredAt = System.currentTimeMillis()
                Log.d(TAG, "Reels viewer entered; grace until ${reelsEnteredAt + REELS_GRACE_MS}")
            }
            Log.d(TAG, "Reels viewer detection: $isInReelsViewer")
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting reels viewer", e)
        }
    }

    private fun isReelsTabSelected(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 15) return false

        val viewId = node.viewIdResourceName
        if (viewId != null && viewId.contains("clips_tab") && node.isSelected) {
            Log.d(TAG, "Reels TAB detected: $viewId isSelected=true")
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isReelsTabSelected(child, depth + 1)
            child.recycle()
            if (found) return true
        }

        return false
    }

    private fun isFullscreenReelViewer(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 15) return false

        val viewId = node.viewIdResourceName
        if (viewId != null) {
            val isReelContainer = viewId.contains("clips") || viewId.contains("reel")
            if (isReelContainer && isNodeFullscreen(node)) {
                Log.d(TAG, "Fullscreen reel viewer detected: $viewId")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isFullscreenReelViewer(child, depth + 1)
            child.recycle()
            if (found) return true
        }

        return false
    }

    private fun isNodeFullscreen(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val metrics = DisplayMetrics()
        val display = getSystemService(android.view.WindowManager::class.java)?.defaultDisplay
        if (display == null) return false
        display.getRealMetrics(metrics)

        val screenArea = metrics.widthPixels.toFloat() * metrics.heightPixels.toFloat()
        val nodeArea = bounds.width().toFloat() * bounds.height().toFloat()
        if (screenArea <= 0) return false

        return (nodeArea / screenArea) >= FULLSCREEN_RATIO
    }

    private fun blockReels() {
        val now = System.currentTimeMillis()
        if (now - lastReelsBlockTime < REELS_COOLDOWN_MS) return

        lastReelsBlockTime = now
        handler.post {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, "Reels blocked - BACK action performed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform back action", e)
            }
        }
        isInReelsViewer = false
    }

    // ---- Lock Home feed: keep the user at the top (Stories only) on the For You tab ----
    // Bounces by pressing the bottom-nav Home button (which on Instagram natively
    // scrolls the feed back to the top — a reload is accepted). The infinite
    // reload loop is avoided by ONLY tapping Home when the feed is genuinely
    // scrolled DOWN. We detect "at top" via the Stories tray: it is visible at the
    // top of the screen when the feed is at the top, and scrolls off when you go
    // down. Tapping Home while already at the top is what triggered the refresh
    // loop, so we skip the tap in that state.

    private fun getScreenWidth(): Int {
        val metrics = DisplayMetrics()
        getSystemService(android.view.WindowManager::class.java)?.defaultDisplay?.getMetrics(metrics)
        return metrics.widthPixels
    }

    private fun findStoriesTray(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenW = getScreenWidth()
        var best: AccessibilityNodeInfo? = null
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || best != null) return
            if (node.isScrollable) {
                val b = Rect()
                node.getBoundsInScreen(b)
                val w = b.width()
                val h = b.height()
                val isHorizontal = w > h
                val nearTop = b.top in 0..400
                val wide = w > screenW * 0.5
                if (isHorizontal && nearTop && wide) {
                    best = node
                    return
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { c -> scan(c, depth + 1); c.recycle() }
            }
        }
        scan(root, 0)
        return best
    }

    private fun findFeedScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestH = 0
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20) return
            if (node.isScrollable) {
                val b = Rect()
                node.getBoundsInScreen(b)
                val h = b.height()
                if (h > bestH) {
                    bestH = h
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                scan(c, depth + 1)
                c.recycle()
            }
        }
        scan(root, 0)
        return best
    }

    // True when the feed is already at the top (so a Home tap would only refresh).
    private fun isFeedAtTop(root: AccessibilityNodeInfo): Boolean {
        val tray = findStoriesTray(root)
        if (tray != null) {
            val b = Rect()
            tray.getBoundsInScreen(b)
            val visible = b.bottom > 0
            tray.recycle()
            return visible
        }
        // Fallback: a feed child sitting at/near the top of the screen means at top.
        val feed = findFeedScrollable(root)
        val child = feed?.getChild(0)
        if (child != null) {
            val b = Rect()
            child.getBoundsInScreen(b)
            val top = b.top
            feed?.recycle()
            child.recycle()
            return top >= 80
        }
        feed?.recycle()
        // Can't tell — be conservative and treat as at top to avoid a refresh loop.
        return true
    }

    private fun applyBlockHomeFeed() {
        val now = System.currentTimeMillis()
        if (now - lastBlockHomeTime < BLOCK_HOME_THROTTLE_MS) return

        // Never interfere while a Reels viewer is open (let Reels blocking handle it).
        if (isInReelsViewer) {
            Log.d(TAG, "LockHome: in Reels viewer, skip")
            return
        }

        val root = getRootInActiveWindow() ?: return
        try {
            val homeBtn = findBottomNavButton(root, "home") ?: findBottomNavButton(root, "casa")
            if (homeBtn == null) {
                Log.d(TAG, "LockHome: Home button not found")
                return
            }
            if (!homeBtn.isSelected && !homeBtn.isChecked) {
                Log.d(TAG, "LockHome: not on Home tab, skip")
                homeBtn.recycle()
                return
            }
            if (isOnFavorites(root)) {
                Log.d(TAG, "LockHome: on Favorites, skip")
                homeBtn.recycle()
                return
            }
            // Only bounce when actually scrolled down. Tapping Home at the top is
            // what caused the infinite pull-to-refresh loop, so skip it here.
            if (isFeedAtTop(root)) {
                Log.d(TAG, "LockHome: at top (Stories tray visible) -> skip, avoid reload loop")
                homeBtn.recycle()
                return
            }
            lastBlockHomeTime = now
            homeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "LockHome: pressed Home -> bounce to top (reload accepted)")
            homeBtn.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error in block home feed", e)
        } finally {
            root.recycle()
        }
    }

    private fun findBottomNavButton(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (result != null || depth > 20) return
            val cd = node.contentDescription?.toString()?.lowercase()
            if (cd != null && cd.contains(label)) {
                result = node
                return
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                scan(c, depth + 1)
                c.recycle()
            }
        }
        scan(root, 0)
        return result
    }

    private fun isOnFavorites(root: AccessibilityNodeInfo): Boolean {
        var found = false
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (found || depth > 20) return
            val t = node.text?.toString()?.lowercase()
            val cd = node.contentDescription?.toString()?.lowercase()
            if ((t != null && (t.contains("preferiti") || t.contains("favorites"))) ||
                (cd != null && (cd.contains("preferiti") || cd.contains("favorites")))) {
                found = true
                return
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                scan(c, depth + 1)
                c.recycle()
            }
        }
        scan(root, 0)
        return found
    }

    override fun onInterrupt() {
        Log.d(TAG, "WhitelistAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        Log.d(TAG, "WhitelistAccessibilityService destroyed")
    }
}
