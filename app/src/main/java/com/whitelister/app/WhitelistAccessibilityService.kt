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
        private const val SKIP_THROTTLE_MS = 1500L
        private val REEL_ID_HINTS = listOf("reel", "clips_video", "reel_viewer", "clips_viewer")
        private val REEL_TEXT_HINTS = setOf("reels", "reel")

        var isRunning = false
            private set

        var instance: WhitelistAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastReelsBlockTime = 0L
    private var lastContentDetectTime = 0L
    private var lastSkipTime = 0L
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
        val skipEnabled = PreferencesManager.isSkipFeedReelsEnabled(this)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (reelsEnabled) {
                    detectReelsViewer()
                } else {
                    isInReelsViewer = false
                }
                if (skipEnabled) applySkipFeedReels()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (reelsEnabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentDetectTime >= CONTENT_DETECT_THROTTLE_MS) {
                        lastContentDetectTime = now
                        detectReelsViewer()
                    }
                }
                if (skipEnabled) applySkipFeedReels()
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
                if (skipEnabled) applySkipFeedReels()
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

    // ---- Skip Reels that appear inline in the feed ----

    private fun applySkipFeedReels() {
        if (!PreferencesManager.isSkipFeedReelsEnabled(this)) return
        val now = System.currentTimeMillis()
        if (now - lastSkipTime < SKIP_THROTTLE_MS) return

        val root = getRootInActiveWindow() ?: return
        try {
            var feedScrollable: AccessibilityNodeInfo? = null
            var reelInfo = ""

            fun scan(node: AccessibilityNodeInfo, depth: Int) {
                if (feedScrollable != null || depth > 18) return
                if (isReelNode(node)) {
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    val visible = b.top < screenHeight() && b.bottom > 0
                    reelInfo = "id=${node.viewIdResourceName} text=${node.text} visible=$visible"
                    Log.d(TAG, "Skip: reel node found ($reelInfo)")
                    if (!visible) return
                    // Walk up to nearest scrollable ancestor.
                    // Vertical -> home feed (skip). Horizontal -> Reels tab pager (ignore).
                    var a: AccessibilityNodeInfo? = node
                    for (step in 0..8) {
                        a = a?.parent ?: break
                        if (a.isScrollable) {
                            if (isVertical(a)) {
                                feedScrollable = AccessibilityNodeInfo.obtain(a)
                                Log.d(TAG, "Skip: feed scrollable found (vertical) -> scroll")
                            } else {
                                Log.d(TAG, "Skip: horizontal pager, ignore")
                            }
                            return
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    scan(c, depth + 1)
                    c.recycle()
                }
            }
            scan(root, 0)

            val fs = feedScrollable
            if (fs != null) {
                fs.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Log.d(TAG, "Skip: scrolled past feed reel")
                fs.recycle()
                lastSkipTime = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in skip feed reels", e)
        } finally {
            root.recycle()
        }
    }

    private fun isReelNode(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase()
        if (id != null) {
            for (h in REEL_ID_HINTS) if (id.contains(h)) return true
        }
        val t = node.text?.toString()?.lowercase()
        val cd = node.contentDescription?.toString()?.lowercase()
        for (h in REEL_TEXT_HINTS) {
            if (t != null && t.contains(h)) return true
            if (cd != null && cd.contains(h)) return true
        }
        return false
    }

    private fun screenHeight(): Int {
        val metrics = DisplayMetrics()
        val display = getSystemService(android.view.WindowManager::class.java)?.defaultDisplay
        display?.getRealMetrics(metrics) ?: return 0
        return metrics.heightPixels
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
