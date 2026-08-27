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
        private const val BLOCK_HOME_THROTTLE_MS = 300L
        private const val AUTO_OPEN_FAV_THROTTLE_MS = 1500L

        var isRunning = false
            private set

        var instance: WhitelistAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastReelsBlockTime = 0L
    private var lastContentDetectTime = 0L
    private var lastBlockHomeTime = 0L
    private var lastAutoOpenFavTime = 0L
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
        val autoOpenFavEnabled = PreferencesManager.isAutoOpenFavoritesEnabled(this)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (reelsEnabled) {
                    detectReelsViewer()
                } else {
                    isInReelsViewer = false
                }
                if (blockHomeEnabled) applyBlockHomeFeed()
                if (autoOpenFavEnabled) applyAutoOpenFavorites()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (reelsEnabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentDetectTime >= CONTENT_DETECT_THROTTLE_MS) {
                        lastContentDetectTime = now
                        detectReelsViewer()
                    }
                }
                if (blockHomeEnabled) applyBlockHomeFeed()
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

    private fun applyBlockHomeFeed() {
        val now = System.currentTimeMillis()
        if (now - lastBlockHomeTime < BLOCK_HOME_THROTTLE_MS) return

        val root = getRootInActiveWindow() ?: return
        try {
            if (!isOnHomeFeed(root)) return
            val tray = findStoriesTray(root) ?: return
            val b = Rect()
            tray.getBoundsInScreen(b)
            // If the Stories tray has scrolled off the top, the user is down in the
            // feed -> bounce back to the top so only Stories remain visible.
            if (b.bottom <= 0) {
                lastBlockHomeTime = now
                val feed = findVerticalScrollableAncestor(tray)
                if (feed != null) {
                    feed.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    Log.d(TAG, "BlockHome: scrolled past stories -> bounce to top")
                    feed.recycle()
                } else {
                    Log.d(TAG, "BlockHome: tray off-screen but no feed scrollable found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in block home feed", e)
        } finally {
            root.recycle()
        }
    }

    private fun isOnHomeFeed(root: AccessibilityNodeInfo): Boolean {
        var found = false
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (found || depth > 18) return
            val t = node.text?.toString()?.lowercase()
            if (t == "per te" || t == "for you") {
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

    private fun findStoriesTray(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (result != null || depth > 18) return
            if (node.isScrollable && isHorizontal(node) && node.childCount >= 4) {
                result = AccessibilityNodeInfo.obtain(node)
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

    private fun findVerticalScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var a: AccessibilityNodeInfo? = node
        for (step in 0..8) {
            a = a?.parent ?: break
            if (a.isScrollable && isVertical(a)) {
                return AccessibilityNodeInfo.obtain(a)
            }
        }
        return null
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

    private fun isHorizontal(node: AccessibilityNodeInfo): Boolean {
        if (node.childCount < 2) return false
        val a = Rect()
        val b = Rect()
        val c1 = node.getChild(0)
        val c2 = node.getChild(1)
        c1?.getBoundsInScreen(a)
        c2?.getBoundsInScreen(b)
        c1?.recycle()
        c2?.recycle()
        return kotlin.math.abs(a.top - b.top) < 200 && a.left < b.left
    }

    // ---- Open Favorites on launch: switch the For You feed to Favorites ----

    private fun applyAutoOpenFavorites() {
        val now = System.currentTimeMillis()
        if (now - lastAutoOpenFavTime < AUTO_OPEN_FAV_THROTTLE_MS) return

        val root = getRootInActiveWindow() ?: return
        try {
            // Only act when on the Home "For You" feed (not already Favorites).
            if (!isOnHomeFeed(root)) return
            var fav: AccessibilityNodeInfo? = null
            fun scan(node: AccessibilityNodeInfo, depth: Int) {
                if (fav != null || depth > 18) return
                val t = node.text?.toString()?.lowercase()
                if (t == "preferiti" || t == "favorites") {
                    fav = AccessibilityNodeInfo.obtain(node)
                    return
                }
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    scan(c, depth + 1)
                    c.recycle()
                }
            }
            scan(root, 0)
            if (fav != null) {
                lastAutoOpenFavTime = now
                fav!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "AutoOpenFav: clicked Favorites")
                fav!!.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in auto open favorites", e)
        } finally {
            root.recycle()
        }
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
