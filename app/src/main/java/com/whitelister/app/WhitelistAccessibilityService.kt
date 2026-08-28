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

@Suppress("DEPRECATION")
class WhitelistAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhitelistService"
        private const val REELS_COOLDOWN_MS = 2000L
        private const val CONTENT_DETECT_THROTTLE_MS = 500L
        private const val FULLSCREEN_RATIO = 0.7f
        private const val BLOCK_HOME_THROTTLE_MS = 1000L
        private const val REELS_GRACE_MS = 1000L
        private const val BOUNCE_SETTLE_MS = 2000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastReelsBlockTime = 0L
    private var lastContentDetectTime = 0L
    private var lastBlockHomeTime = 0L
    private var isInReelsViewer = false
    private var reelsEnteredAt = 0L
    private var lastBounceAt = 0L
    private var sawTopSinceBounce = false
    // True while the Instagram in-app browser (BrowserLiteInMainProcessIGActivity) is
    // the active window. Kept in sync on TYPE_WINDOW_STATE_CHANGED so neither Reels
    // blocking nor Lock Home Feed can act while the user browses an external website.
    private var inIgBrowser = false

    private fun logD(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun logE(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) Log.e(TAG, message, throwable)
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

        logD("WhitelistAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName != "com.instagram.android") return

        val reelsEnabled = PreferencesManager.isReelsBlockingEnabled(this)
        val blockHomeEnabled = PreferencesManager.isBlockHomeFeedEnabled(this)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                inIgBrowser = event.className?.toString()?.
                    lowercase()?.contains("inappbrowser") == true
                if (reelsEnabled) {
                    detectReelsViewer()
                } else {
                    isInReelsViewer = false
                }
                if (blockHomeEnabled) {
                    // A new Instagram window means a fresh session: never inherit
                    // stale lock-home state from before. Reset the transient fields
                    // and evaluate once (without pressing) so the machine starts
                    // from a consistent state on the very first interaction.
                    lastBlockHomeTime = 0
                    lastBounceAt = 0
                    sawTopSinceBounce = false
                    applyBlockHomeFeed(evaluateOnly = true)
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
                            logD("Reels: within grace ($sinceEnter ms), ignore scroll")
                        }
                    }
                }
                if (blockHomeEnabled) applyBlockHomeFeed()
            }
        }
    }

    private fun detectReelsViewer() {
        if (isIgBrowserActive(null)) {
            isInReelsViewer = false
            return
        }
        val wasInViewer = isInReelsViewer
        isInReelsViewer = false

        try {
            val rootNode = getRootInActiveWindow() ?: return
            if (isIgBrowserActive(rootNode)) {
                isInReelsViewer = false
                rootNode.recycle()
                return
            }
            isInReelsViewer = isReelsTabSelected(rootNode, 0) ||
                    isFullscreenReelViewer(rootNode, 0)
            rootNode.recycle()
            // On entering the viewer, arm the grace period so the scroll event
            // emitted by simply opening a reel doesn't immediately back out.
            if (isInReelsViewer && !wasInViewer) {
                reelsEnteredAt = System.currentTimeMillis()
                logD("Reels viewer entered; grace until ${reelsEnteredAt + REELS_GRACE_MS}")
            }
            logD("Reels viewer detection: $isInReelsViewer")
        } catch (e: Exception) {
            logE("Error detecting reels viewer", e)
        }
    }

    private fun isReelsTabSelected(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 15) return false

        val viewId = node.viewIdResourceName
        if (viewId != null && viewId.contains("clips_tab") && node.isSelected) {
            logD("Reels TAB detected: $viewId isSelected=true")
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
                logD("Fullscreen reel viewer detected: $viewId")
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

    // True when the Instagram in-app browser is on screen. The activity-level state
    // (tracked from TYPE_WINDOW_STATE_CHANGED class names) covers the normal case;
    // the tree scan is a fallback for events that arrive without a preceding window
    // change, matching the browser's WebView classes or its BrowserLite activity name.
    private fun isIgBrowserActive(root: AccessibilityNodeInfo?): Boolean {
        if (inIgBrowser) return true
        return root?.let { containsBrowserClass(it, 0) } ?: false
    }

    private fun containsBrowserClass(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 25) return false
        val cls = node.className?.toString()?.lowercase()
        if (cls != null && (cls.contains("webview") || cls.contains("browser"))) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = containsBrowserClass(c, depth + 1)
            c.recycle()
            if (found) return true
        }
        return false
    }

    private fun blockReels() {
        val now = System.currentTimeMillis()
        if (now - lastReelsBlockTime < REELS_COOLDOWN_MS) return

        lastReelsBlockTime = now
        handler.post {
            var root: AccessibilityNodeInfo? = null
            try {
                root = getRootInActiveWindow()
                if (isIgBrowserActive(root)) {
                    return@post
                }
                performGlobalAction(GLOBAL_ACTION_BACK)
                logD("Reels blocked - BACK action performed")
            } catch (e: Exception) {
                logE("Failed to perform back action", e)
            } finally {
                root?.recycle()
            }
        }
        isInReelsViewer = false
    }

    // ---- Lock Home feed: keep the user at the top (Stories only) on the For You tab ----
    // Bounces by pressing the bottom-nav Home button (which on Instagram natively
    // scrolls the feed back to the top). "At top" is detected via the Stories tray
    // (a wide horizontal scrollable): diagnostic data showed it exists at the top
    // of the feed, scrolls up, and is REMOVED from the accessibility tree entirely
    // once you scroll past it. So tray present + visible -> at top, do nothing;
    // tray gone -> scrolled down, press Home.
    // Loop protection: after a bounce we hold for BOUNCE_SETTLE_MS and only press
    // again after the tray has been SEEN at top again. If the user keeps scrolling
    // (bypass) and the tray never comes back, we detect that and press Home too,
    // so the lock fights back while the feed never reaches a settled top.

    private fun getScreenWidth(): Int {
        val metrics = DisplayMetrics()
        getSystemService(android.view.WindowManager::class.java)?.defaultDisplay?.getMetrics(metrics)
        return metrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        val metrics = DisplayMetrics()
        getSystemService(android.view.WindowManager::class.java)?.defaultDisplay?.getMetrics(metrics)
        return metrics.heightPixels
    }

    // True when a bottom-nav tab other than Home is the selected one. Only nodes in
    // the bottom band of the screen count, so feed elements that happen to mention
    // matching words (e.g. "profile", "search") cannot be mistaken for the nav bar.
    private fun isOtherNavTabSelected(root: AccessibilityNodeInfo): Boolean {
        val screenH = getScreenHeight()
        val otherLabels = listOf(
            "search", "ricerca", "cerca",
            "reel", "clips",
            "shop", "negozio",
            "profile", "profilo"
        )
        var selected = false
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (selected || depth > 20) return
            val cd = node.contentDescription?.toString()?.lowercase()
            if (cd != null && otherLabels.any { cd.contains(it) } && (node.isSelected || node.isChecked)) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (!b.isEmpty && b.top > screenH * 0.72f) {
                    selected = true
                    return
                }
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                scan(c, depth + 1)
                c.recycle()
            }
        }
        scan(root, 0)
        return selected
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
                // >= 400px above the app bar counts as "still at/near the top" so a
                // partially-scrolled-off tray is still recognised until it is gone.
                val nearTop = b.top > -400 && b.top < 600
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

    // True when the feed is already at the top (so the service must not press Home).
    private fun isFeedAtTop(root: AccessibilityNodeInfo): Boolean {
        val tray = findStoriesTray(root)
        if (tray != null) {
            val b = Rect()
            tray.getBoundsInScreen(b)
            val visible = b.bottom > 0 && b.top < b.bottom
            tray.recycle()
            return visible
        }
        return false
    }

    private fun applyBlockHomeFeed(evaluateOnly: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastBlockHomeTime < BLOCK_HOME_THROTTLE_MS) return

        // Never interfere while the in-app browser is open: the bottom-nav "home"
        // button can be present behind/above the browser (or a web "home" element can
        // match), so pressing here would bounce the user out of the website.
        if (isIgBrowserActive(null)) {
            return
        }

        // Never interfere while a Reels viewer is open (let Reels blocking handle it).
        if (isInReelsViewer) {
            logD("LockHome: in Reels viewer, skip")
            return
        }

        val root = getRootInActiveWindow() ?: return
        try {
            if (isIgBrowserActive(root)) {
                return
            }
            val homeBtn = findBottomNavButton(root, "home") ?: findBottomNavButton(root, "casa")
            if (homeBtn == null) {
                logD("LockHome: Home button not found")
                return
            }
            val homeSelected = homeBtn.isSelected || homeBtn.isChecked
            val otherTabSelected = !homeSelected && isOtherNavTabSelected(root)
            if (!homeSelected && otherTabSelected) {
                logD("LockHome: not on Home tab, skip")
                homeBtn.recycle()
                return
            }
            if (!homeSelected && !otherTabSelected) {
                // Cold-start quirk: on a freshly opened Instagram no nav tab is
                // reported as selected yet, but the Home button exists and no other
                // tab is selected, so treat the feed as Home.
                logD("LockHome: no nav tab selected yet, assuming Home (cold start)")
            }
            if (isOnFavorites(root)) {
                logD("LockHome: on Favorites, skip")
                homeBtn.recycle()
                return
            }
            // At the top: tray present and visible -> never press here (a press at
            // the top is what causes the pull-to-refresh loop).
            if (isFeedAtTop(root)) {
                sawTopSinceBounce = true
                logD("LockHome: at top (Stories tray visible), re-armed")
                homeBtn.recycle()
                return
            }
            // Past the Stories (tray gone). If the tray has been observed at top
            // since the last bounce, this is a normal scrolled-down -> bounce.
            if (sawTopSinceBounce) {
                if (now - lastBounceAt < BOUNCE_SETTLE_MS) {
                    logD("LockHome: settling after bounce, hold")
                    homeBtn.recycle()
                    return
                }
                if (evaluateOnly) {
                    logD("LockHome: eval-only, would bounce to top")
                    homeBtn.recycle()
                    return
                }
                lastBlockHomeTime = now
                lastBounceAt = now
                sawTopSinceBounce = false
                homeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                logD("LockHome: pressed Home -> bounce to top (reload accepted)")
                homeBtn.recycle()
                return
            }
            // Bypass catch: the tray has NOT been seen at top since the last bounce,
            // yet we are not at top either — the user scrolled through the snap-back.
            // Lock them again (only after the settle window so the return animation
            // of the previous bounce does not cause a press of its own).
            if (now - lastBounceAt < BOUNCE_SETTLE_MS) {
                logD("LockHome: settling after bounce, hold")
                homeBtn.recycle()
                return
            }
            if (evaluateOnly) {
                logD("LockHome: eval-only, would bounce (bypass)")
                homeBtn.recycle()
                return
            }
            lastBlockHomeTime = now
            lastBounceAt = now
            homeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            logD("LockHome: bypass detected -> pressed Home (lock again)")
            homeBtn.recycle()
        } catch (e: Exception) {
            logE("Error in block home feed", e)
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
        logD("WhitelistAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        logD("WhitelistAccessibilityService destroyed")
    }
}