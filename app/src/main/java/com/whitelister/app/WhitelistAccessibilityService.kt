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

        private const val HIDE_THROTTLE_MS = 1200L
        private const val HIDE_ACTION_DELAY_MS = 400L
        private const val HIDE_COOLDOWN_MS = 5000L

        private val PROMOTED_KEYWORDS = setOf(
            "sponsored", "patrocinato", "paid partnership", "publicità", "promoted"
        )
        private val SUGGESTED_KEYWORDS = setOf(
            "suggested for you", "consigliato per te", "ti consigliamo", "suggested post"
        )
        private val REELS_ID_HINTS = listOf("reel", "clips_video", "reel_viewer", "clips_viewer")
        private val MENU_KEYWORDS = setOf(
            "hide ad", "nascondi pubblicità", "not interested", "non mi interessa", "hide"
        )
        private val KEBAB_TEXT_HINTS = setOf(
            "more options", "opzioni", "altro", "menu"
        )
        private val KEBAB_ID_HINTS = listOf(
            "action_button", "overflow", "menu_button", "button_more"
        )

        var isRunning = false
            private set

        var instance: WhitelistAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastReelsBlockTime = 0L
    private var lastContentDetectTime = 0L
    private var isInReelsViewer = false
    private var lastHideTime = 0L
    private val actedHashes = mutableMapOf<String, Long>()

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
        val hideEnabled = PreferencesManager.isHidePromotedEnabled(this)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (reelsEnabled) {
                    detectReelsViewer()
                } else {
                    isInReelsViewer = false
                }
                if (hideEnabled) applyHidePromoted()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (reelsEnabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentDetectTime >= CONTENT_DETECT_THROTTLE_MS) {
                        lastContentDetectTime = now
                        detectReelsViewer()
                    }
                }
                if (hideEnabled) applyHidePromoted()
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
                if (hideEnabled) applyHidePromoted()
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

    // ---- Hide Sponsored & Suggested (+ inline Reels) ----

    private fun applyHidePromoted() {
        if (!PreferencesManager.isHidePromotedEnabled(this)) return
        val now = System.currentTimeMillis()
        if (now - lastHideTime < HIDE_THROTTLE_MS) return
        lastHideTime = now

        val root = getRootInActiveWindow() ?: return
        try {
            var targetKebab: AccessibilityNodeInfo? = null
            var targetKey: String? = null

            fun scan(node: AccessibilityNodeInfo, depth: Int) {
                if (targetKebab != null || depth > 18) return
                if (nodeHasLabel(node)) {
                    var a: AccessibilityNodeInfo? = node
                    for (step in 0..6) {
                        a ?: break
                        val k = findKebab(a)
                        if (k != null) {
                            val key = hashKey(k)
                            val last = actedHashes[key] ?: 0L
                            if (now - last >= HIDE_COOLDOWN_MS) {
                                targetKebab = k
                                targetKey = key
                            } else {
                                k.recycle()
                            }
                            break
                        }
                        a = a.parent
                    }
                }
                if (targetKebab != null) return
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    scan(c, depth + 1)
                    c.recycle()
                }
            }
            scan(root, 0)

            val kebab = targetKebab
            val key = targetKey
            if (kebab != null && key != null) {
                actedHashes[key] = now
                kebab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Hide: opened menu for promoted/suggested/reel card")
                handler.postDelayed({
                    try {
                        val r2 = getRootInActiveWindow() ?: return@postDelayed
                        val item = findMenuAction(r2)
                        if (item != null) {
                            item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            item.recycle()
                            Log.d(TAG, "Hide: selected hide/not-interested action")
                        } else {
                            Log.d(TAG, "Hide: no matching menu item found")
                        }
                        r2.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Hide menu click failed", e)
                    }
                }, HIDE_ACTION_DELAY_MS)
                kebab.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in hide promoted", e)
        } finally {
            root.recycle()
            val cut = System.currentTimeMillis()
            val it = actedHashes.entries.iterator()
            while (it.hasNext()) {
                if (cut - it.next().value > HIDE_COOLDOWN_MS) it.remove()
            }
        }
    }

    private fun nodeHasLabel(node: AccessibilityNodeInfo): Boolean {
        val t = node.text?.toString()?.lowercase()
        val cd = node.contentDescription?.toString()?.lowercase()
        val id = node.viewIdResourceName?.lowercase()
        for (kw in PROMOTED_KEYWORDS) {
            if (t != null && t.contains(kw)) return true
            if (cd != null && cd.contains(kw)) return true
        }
        for (kw in SUGGESTED_KEYWORDS) {
            if (t != null && t.contains(kw)) return true
            if (cd != null && cd.contains(kw)) return true
        }
        if (id != null) {
            for (h in REELS_ID_HINTS) if (id.contains(h)) return true
        }
        return false
    }

    private fun findKebab(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) {
            val cd = node.contentDescription?.toString()?.lowercase()
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if (cd != null) {
                for (h in KEBAB_TEXT_HINTS) if (cd.contains(h)) {
                    return AccessibilityNodeInfo.obtain(node)
                }
            }
            if (id.contains("action_button") || id.contains("overflow") ||
                id.contains("menu_button") || id.contains("button_more")
            ) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findKebab(c)
            c.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findMenuAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = node.text?.toString()?.lowercase()
        if (node.isClickable && t != null) {
            for (kw in MENU_KEYWORDS) {
                if (t.contains(kw)) return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findMenuAction(c)
            c.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun hashKey(node: AccessibilityNodeInfo): String {
        val b = Rect()
        node.getBoundsInScreen(b)
        return "${node.viewIdResourceName ?: ""}_${b.left}_${b.top}_${b.right}_${b.bottom}"
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
