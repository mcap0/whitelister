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
        private const val BLOCK_HOME_THROTTLE_MS = 500L

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
                        blockReels()
                    }
                }
                if (blockHomeEnabled) applyBlockHomeFeed()
            }
        }
    }

    private fun detectReelsViewer() {
        isInReelsViewer = false

        try {
            val rootNode = getRootInActiveWindow() ?: return
            isInReelsViewer = isReelsTabSelected(rootNode, 0) ||
                    isFullscreenReelViewer(rootNode, 0)
            rootNode.recycle()
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
    // Works by pressing the bottom-nav Home button, which on Instagram natively
    // scrolls the feed back to the top. Detection keys off the Home button's
    // isSelected state (not fragile UI text).

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
            if (!homeBtn.isSelected) {
                Log.d(TAG, "LockHome: not on Home tab, skip")
                return
            }
            if (isOnFavorites(root)) {
                Log.d(TAG, "LockHome: on Favorites, skip")
                return
            }
            // Only bounce if the feed is actually scrolled down. Tapping Home while
            // already at the top would reload the feed and loop when idle.
            if (isFeedAtTop(root)) {
                Log.d(TAG, "LockHome: already at top, skip")
                return
            }
            lastBlockHomeTime = now
            homeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "LockHome: pressed Home -> bounce to top")
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

    private fun isVertical(node: AccessibilityNodeInfo): Boolean {
        if (node.childCount < 2) return true
        val a = Rect()
        val b = Rect()
        val c1 = node.getChild(0)
        val c2 = node.getChild(1)
        c1?.getBoundsInScreen(a)
        c2?.getBoundsInScreen(b)
        c1?.recycle()
        c2?.recycle()
        return a.top < b.top && kotlin.math.abs(a.left - b.left) < 200
    }

    private fun findMainFeedScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestH = 0
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20) return
            if (node.isScrollable && isVertical(node)) {
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

    private fun isFeedAtTop(root: AccessibilityNodeInfo): Boolean {
        val feed = findMainFeedScrollable(root) ?: return true
        val child = feed.getChild(0) ?: return true
        val b = Rect()
        child.getBoundsInScreen(b)
        child.recycle()
        // At (or very near) the top when the first item's top is still on/above screen top.
        return b.top >= -8
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
