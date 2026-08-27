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
        private const val AUTOPLAY_THROTTLE_MS = 1000L
        private const val INFINITE_SCROLL_CAP = 30
        private const val INFINITE_SCROLL_COOLDOWN_MS = 3000L

        var isRunning = false
            private set

        var instance: WhitelistAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastReelsBlockTime = 0L
    private var lastContentDetectTime = 0L
    private var lastAutoplayTime = 0L
    private var lastInfiniteBackTime = 0L
    private var scrollCount = 0
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
        val autoplayEnabled = PreferencesManager.isAutoplayOffEnabled(this)
        val infiniteScrollEnabled = PreferencesManager.isInfiniteScrollOffEnabled(this)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (reelsEnabled) {
                    detectReelsViewer()
                } else {
                    isInReelsViewer = false
                }
                scrollCount = 0
                if (autoplayEnabled) applyAutoplayOff()
                if (infiniteScrollEnabled) applyInfiniteScrollOff()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (reelsEnabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentDetectTime >= CONTENT_DETECT_THROTTLE_MS) {
                        lastContentDetectTime = now
                        detectReelsViewer()
                    }
                }
                if (autoplayEnabled) applyAutoplayOff()
                if (infiniteScrollEnabled) applyInfiniteScrollOff()
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
                scrollCount++
                if (autoplayEnabled) applyAutoplayOff()
                if (infiniteScrollEnabled) applyInfiniteScrollOff()
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

    // ---- Disable Autoplay: pause videos/reels that start playing in the feed ----

    private fun applyAutoplayOff() {
        val now = System.currentTimeMillis()
        if (now - lastAutoplayTime < AUTOPLAY_THROTTLE_MS) return
        lastAutoplayTime = now

        val root = getRootInActiveWindow() ?: return
        try {
            var target: AccessibilityNodeInfo? = null
            var targetInfo = ""

            fun scan(node: AccessibilityNodeInfo, depth: Int) {
                if (target != null || depth > 18) return
                if (isPlayingVideoNode(node)) {
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    val sh = screenHeight()
                    val visible = b.top < sh && b.bottom > 0
                    val areaRatio = if (sh > 0) b.height().toFloat() / sh.toFloat() else 0f
                    if (visible && areaRatio > 0.3f) {
                        target = AccessibilityNodeInfo.obtain(node)
                        targetInfo = "class=${node.className} id=${node.viewIdResourceName} text=${node.text}"
                        Log.d(TAG, "Autoplay: video node found ($targetInfo)")
                    }
                }
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    scan(c, depth + 1)
                    c.recycle()
                }
            }
            scan(root, 0)

            val t = target
            if (t != null) {
                // Try to find an explicit play/pause control first.
                var pauseControl: AccessibilityNodeInfo? = null
                fun findPause(n: AccessibilityNodeInfo, depth: Int) {
                    if (pauseControl != null || depth > 6) return
                    val cd = n.contentDescription?.toString()?.lowercase()
                    val txt = n.text?.toString()?.lowercase()
                    if ((cd != null && (cd.contains("pause") || cd.contains("play"))) ||
                        (txt != null && (txt.contains("pause") || txt.contains("play")))) {
                        pauseControl = AccessibilityNodeInfo.obtain(n)
                        return
                    }
                    for (i in 0 until n.childCount) {
                        val c = n.getChild(i) ?: continue
                        findPause(c, depth + 1)
                        c.recycle()
                    }
                }
                findPause(t, 0)

                if (pauseControl != null) {
                    pauseControl!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Autoplay: pause control clicked")
                    pauseControl!!.recycle()
                } else {
                    // Fallback: tap the media itself. For a reel this may open the
                    // viewer, which the Reels back-block then closes.
                    t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Autoplay: no pause control -> clicked media (fallback)")
                }
                t.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in autoplay off", e)
        } finally {
            root.recycle()
        }
    }

    private fun isPlayingVideoNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString()?.lowercase() ?: ""
        if (cls.contains("video") || cls.contains("texture") || cls.contains("surface") || cls.contains("exo")) return true
        val t = node.text?.toString()?.lowercase()
        val cd = node.contentDescription?.toString()?.lowercase()
        if (t == "reel" || t == "video" || cd == "reel" || cd == "video") return true
        if (t != null && t.contains("reel")) return true
        if (cd != null && cd.contains("reel")) return true
        return false
    }

    private fun screenHeight(): Int {
        val metrics = DisplayMetrics()
        val display = getSystemService(android.view.WindowManager::class.java)?.defaultDisplay
        display?.getRealMetrics(metrics) ?: return 0
        return metrics.heightPixels
    }

    // ---- Limit Infinite Scroll: leave the feed after extended scrolling ----

    private fun applyInfiniteScrollOff() {
        if (scrollCount < INFINITE_SCROLL_CAP) return
        val now = System.currentTimeMillis()
        if (now - lastInfiniteBackTime < INFINITE_SCROLL_COOLDOWN_MS) return
        lastInfiniteBackTime = now
        scrollCount = 0
        Log.d(TAG, "InfiniteScroll: cap reached ($INFINITE_SCROLL_CAP) -> BACK to exit feed")
        handler.post {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                Log.e(TAG, "Failed infinite scroll back", e)
            }
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
