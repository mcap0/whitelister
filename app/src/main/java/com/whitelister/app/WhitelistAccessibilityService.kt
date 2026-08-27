package com.whitelister.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhitelistAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhitelistService"
        private const val REELS_COOLDOWN_MS = 2000L
        private const val CONTENT_DETECT_THROTTLE_MS = 500L
        private const val FULLSCREEN_RATIO = 0.7f
        private const val FEED_SCROLL_COOLDOWN_MS = 1200L
        private const val FEED_CARD_MIN_RATIO = 0.3f
        private const val FEED_OVERLAY_COLOR = Color.BLACK
        private const val FEED_AUTOSCROLL_ENABLED = false

        var isRunning = false
            private set

        var instance: WhitelistAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastReelsBlockTime = 0L
    private var lastContentDetectTime = 0L
    private var lastFeedScrollTime = 0L
    private var isInReelsViewer = false
    private var isFeedFiltering = false

    private lateinit var windowManager: WindowManager
    private val overlayWindows = mutableListOf<View>()
    private var screenW = 0
    private var screenH = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay?.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

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
        if (event.packageName != "com.instagram.android") {
            clearOverlays()
            return
        }

        val reelsEnabled = PreferencesManager.isReelsBlockingEnabled(this)
        val feedEnabled = PreferencesManager.isFeedFilteringEnabled(this)
        isFeedFiltering = feedEnabled

        if (!reelsEnabled) isInReelsViewer = false

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (reelsEnabled) {
                    detectReelsViewer()
                } else {
                    isInReelsViewer = false
                }
                if (isInReelsViewer) clearOverlays()
                if (feedEnabled) applyFeedFiltering()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentDetectTime >= CONTENT_DETECT_THROTTLE_MS) {
                    lastContentDetectTime = now
                    if (reelsEnabled) detectReelsViewer()
                    if (feedEnabled) applyFeedFiltering()
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
                        return
                    }
                }
                if (feedEnabled) applyFeedFiltering()
            }
        }
    }

    // ---------- Reels blocking (unchanged logic) ----------

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
        val display = windowManager.defaultDisplay
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

    // ---------- Feed whitelisting (overlay + auto-scroll) ----------

    private fun applyFeedFiltering() {
        if (!isFeedFiltering) {
            clearOverlays()
            return
        }

        val root = getRootInActiveWindow() ?: run { clearOverlays(); return }

        try {
            if (!isOnHomeTab(root)) {
                clearOverlays()
                return
            }
            val feed = findFeedScrollable(root) ?: run { clearOverlays(); return }

            val whitelist = PreferencesManager.getWhitelistedAccounts(this)
                .map { it.lowercase() }
                .toSet()

            val blocked = mutableListOf<Rect>()
            for (i in 0 until feed.childCount) {
                val child = feed.getChild(i) ?: continue
                val username = extractUsername(child)
                if (username != null) {
                    val b = Rect()
                    child.getBoundsInScreen(b)
                    if (b.height() > screenH * FEED_CARD_MIN_RATIO) {
                        val norm = username.lowercase()
                        if (whitelist.contains(norm)) {
                            Log.d(TAG, "Whitelisted feed post: @$norm")
                        } else {
                            Log.d(TAG, "Blocked feed post: @$norm")
                            blocked.add(b)
                        }
                    }
                }
                child.recycle()
            }
            feed.recycle()

            val unique = dedupeRects(blocked)
            clearOverlays()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (rect in unique) addOverlay(rect)
            }

            if (unique.isNotEmpty() && FEED_AUTOSCROLL_ENABLED) {
                maybeAutoScroll()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in feed filtering", e)
        } finally {
            root.recycle()
        }
    }

    private fun isOnHomeTab(root: AccessibilityNodeInfo): Boolean {
        if (detectStoriesTray(root)) return true
        var homeSelected = false
        var otherSelected = false
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20) return
            if (node.isSelected) {
                val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                    .lowercase()
                when {
                    label.contains("home") -> homeSelected = true
                    label.contains("reels") || label.contains("search") ||
                            label.contains("explore") || label.contains("messages") ||
                            label.contains("inbox") || label.contains("profile") ||
                            label.contains("create") || label.contains("new post") -> otherSelected = true
                }
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                visit(c, depth + 1)
                c.recycle()
            }
        }
        visit(root, 0)
        return homeSelected && !otherSelected
    }

    private fun detectStoriesTray(root: AccessibilityNodeInfo): Boolean {
        var found = false
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (found || depth > 20) return
            if (node.isScrollable) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.top < screenH * 0.25f && b.height() < screenH * 0.2f) {
                    var smallAvatars = 0
                    for (i in 0 until node.childCount) {
                        val c = node.getChild(i) ?: continue
                        val cb = Rect()
                        c.getBoundsInScreen(cb)
                        if (cb.height() < screenH * 0.15f) smallAvatars++
                        c.recycle()
                    }
                    if (smallAvatars >= 3) found = true
                    return
                }
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                visit(c, depth + 1)
                c.recycle()
            }
        }
        visit(root, 0)
        return found
    }

    private fun findFeedScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (result != null || depth > 20) return
            if (node.isScrollable) {
                var fullScreenChild = false
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    val b = Rect()
                    c.getBoundsInScreen(b)
                    if (b.height() > screenH * 0.9f && b.width() > screenW * 0.9f) {
                        fullScreenChild = true
                    }
                    c.recycle()
                }
                if (!fullScreenChild && node.childCount >= 2) {
                    val a = Rect()
                    val b = Rect()
                    val c1 = node.getChild(0)
                    val c2 = node.getChild(1)
                    c1?.getBoundsInScreen(a)
                    c2?.getBoundsInScreen(b)
                    c1?.recycle()
                    c2?.recycle()
                    val vertical = a.top < b.top &&
                            kotlin.math.abs(a.left - b.left) < screenW * 0.2f
                    if (vertical) {
                        result = AccessibilityNodeInfo.obtain(node)
                        return
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                visit(c, depth + 1)
                c.recycle()
            }
        }
        visit(root, 0)
        return result
    }

    private fun extractUsername(node: AccessibilityNodeInfo): String? {
        var found: String? = null

        fun dfs(n: AccessibilityNodeInfo, depth: Int) {
            if (found != null || depth > 10) return
            val cls = n.className?.toString() ?: ""
            if (cls.contains("Image") || cls.contains("Avatar")) {
                val cd = n.contentDescription?.toString()
                if (cd != null) {
                    val parsed = parseUsername(cd)
                    if (parsed != null) {
                        found = parsed
                        return
                    }
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                dfs(c, depth + 1)
                c.recycle()
                if (found != null) return
            }
        }

        dfs(node, 0)
        return found
    }

    private fun parseUsername(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.contains(' ') && Regex("^[A-Za-z0-9._]+$").matches(s)) {
            return s.removePrefix("@").lowercase()
        }
        val m = Regex("(?i)(?:by\\s+|@)([a-z0-9._]+)").find(s)
        if (m != null) return m.groupValues[1].lowercase()
        val m2 = Regex("(?i)^([a-z0-9._]+)'").find(s)
        if (m2 != null) return m2.groupValues[1].lowercase()
        return null
    }

    private fun dedupeRects(rects: List<Rect>): List<Rect> {
        val unique = mutableListOf<Rect>()
        for (r in rects) {
            val overlaps = unique.any { existing ->
                val interLeft = maxOf(r.left, existing.left)
                val interTop = maxOf(r.top, existing.top)
                val interRight = minOf(r.right, existing.right)
                val interBottom = minOf(r.bottom, existing.bottom)
                val interArea = maxOf(0, interRight - interLeft) * maxOf(0, interBottom - interTop)
                val area = r.width() * r.height()
                area > 0 && (interArea.toFloat() / area) > 0.5f
            }
            if (!overlaps) unique.add(r)
        }
        return unique
    }

    private fun addOverlay(rect: Rect) {
        try {
            val view = View(this)
            view.setBackgroundColor(FEED_OVERLAY_COLOR)
            val params = WindowManager.LayoutParams(
                rect.width(),
                rect.height(),
                rect.left,
                rect.top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(view, params)
            overlayWindows.add(view)
            Log.d(TAG, "Overlay added at $rect")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
        }
    }

    private fun clearOverlays() {
        for (v in overlayWindows) {
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlayWindows.clear()
    }

    private fun maybeAutoScroll() {
        val now = System.currentTimeMillis()
        if (now - lastFeedScrollTime < FEED_SCROLL_COOLDOWN_MS) return
        lastFeedScrollTime = now
        val root = getRootInActiveWindow() ?: return
        try {
            if (!isOnHomeTab(root)) return
            val feed = findFeedScrollable(root) ?: return
            if (feed.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                Log.d(TAG, "Feed auto-scroll performed")
            } else {
                Log.d(TAG, "Feed auto-scroll action not performed")
            }
            feed.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-scroll feed", e)
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "WhitelistAccessibilityService interrupted")
    }

    override fun onDestroy() {
        clearOverlays()
        super.onDestroy()
        isRunning = false
        instance = null
        Log.d(TAG, "WhitelistAccessibilityService destroyed")
    }
}
