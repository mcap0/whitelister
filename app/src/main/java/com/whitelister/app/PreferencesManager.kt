package com.whitelister.app

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * The surfaces a feature can block and whose daily usage is metered: Instagram's (REELS, FEED,
 * EXPLORE) and Facebook's (FB_REELS, FB_FEED).
 */
enum class IgSurface(val key: String) {
    REELS("reels"),
    FEED("feed"),
    EXPLORE("explore"),
    FB_REELS("fb_reels"),
    FB_FEED("fb_feed")
}

object PreferencesManager {
    private const val PREFS_NAME = "whitelister_prefs"
    private const val KEY_REELS_BLOCKING = "reels_blocking_enabled"
    private const val KEY_BLOCK_FEED = "block_feed_enabled"
    private const val KEY_BLOCK_EXPLORE = "block_explore_enabled"
    private const val KEY_FB_REELS_BLOCKING = "fb_reels_blocking_enabled"
    private const val KEY_FB_BLOCK_FEED = "fb_block_feed_enabled"

    /**
     * Version of the in-app accessibility disclosure the user accepted (0 = never). Google Play
     * requires affirmative consent before the service reads any screen content, and the consent
     * must cover what is actually read: bump [CONSENT_VERSION] whenever the disclosure changes
     * materially so everyone is asked again. v1 (1.0.5, key "consent_accepted") covered Instagram
     * only; v2 (1.5.0) adds Facebook, Block Feed/Explore and the daily usage meter; v3 (1.6.1)
     * adds the Facebook Home feed (Block Feed on Facebook).
     */
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val CONSENT_VERSION = 3

    /** Daily free minutes before a surface's block engages: "<surface>_free_minutes". */
    private const val SUFFIX_FREE_MINUTES = "_free_minutes"
    /** Milliseconds spent on a surface today: "<surface>_used_ms", valid for "<surface>_used_day". */
    private const val SUFFIX_USED_MS = "_used_ms"
    private const val SUFFIX_USED_DAY = "_used_day"

    /** Choices offered in the UI. 0 = no free time, the block is always on. */
    val FREE_MINUTES_OPTIONS = listOf(0, 5, 10, 15, 20, 30, 45, 60)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isReelsBlockingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REELS_BLOCKING, false)

    fun setReelsBlockingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REELS_BLOCKING, enabled).apply()
    }

    fun isBlockFeedEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_FEED, false)

    fun setBlockFeedEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_FEED, enabled).apply()
    }

    fun isBlockExploreEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_EXPLORE, false)

    fun setBlockExploreEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_EXPLORE, enabled).apply()
    }

    /** True once the user accepted the current accessibility disclosure. */
    fun hasConsent(context: Context): Boolean =
        prefs(context).getInt(KEY_CONSENT_VERSION, 0) >= CONSENT_VERSION

    fun setConsentGiven(context: Context) {
        prefs(context).edit().putInt(KEY_CONSENT_VERSION, CONSENT_VERSION).apply()
    }

    fun isFbReelsBlockingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FB_REELS_BLOCKING, false)

    fun setFbReelsBlockingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FB_REELS_BLOCKING, enabled).apply()
    }

    fun isFbBlockFeedEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FB_BLOCK_FEED, false)

    fun setFbBlockFeedEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FB_BLOCK_FEED, enabled).apply()
    }

    /** True when any Facebook feature is on (Facebook is handled and metered only then). */
    fun isAnyFacebookEnabled(context: Context): Boolean =
        isFbReelsBlockingEnabled(context) || isFbBlockFeedEnabled(context)

    fun isEnabled(context: Context, surface: IgSurface): Boolean = when (surface) {
        IgSurface.REELS -> isReelsBlockingEnabled(context)
        IgSurface.FEED -> isBlockFeedEnabled(context)
        IgSurface.EXPLORE -> isBlockExploreEnabled(context)
        IgSurface.FB_REELS -> isFbReelsBlockingEnabled(context)
        IgSurface.FB_FEED -> isFbBlockFeedEnabled(context)
    }

    fun getFreeMinutes(context: Context, surface: IgSurface): Int =
        prefs(context).getInt(surface.key + SUFFIX_FREE_MINUTES, 0)

    fun setFreeMinutes(context: Context, surface: IgSurface, minutes: Int) {
        prefs(context).edit().putInt(surface.key + SUFFIX_FREE_MINUTES, minutes).apply()
    }

    /** Milliseconds spent on [surface] today (resets at local midnight). */
    fun getUsedMsToday(context: Context, surface: IgSurface): Long {
        val p = prefs(context)
        if (p.getInt(surface.key + SUFFIX_USED_DAY, -1) != today()) return 0L
        return p.getLong(surface.key + SUFFIX_USED_MS, 0L)
    }

    fun addUsedMs(context: Context, surface: IgSurface, ms: Long) {
        if (ms <= 0) return
        val used = getUsedMsToday(context, surface) + ms
        prefs(context).edit()
            .putInt(surface.key + SUFFIX_USED_DAY, today())
            .putLong(surface.key + SUFFIX_USED_MS, used)
            .apply()
    }

    /**
     * True when [surface]'s block should act right now: the feature is on and today's free
     * time (if any) is used up.
     */
    fun shouldBlock(context: Context, surface: IgSurface): Boolean {
        if (!isEnabled(context, surface)) return false
        val freeMs = getFreeMinutes(context, surface) * 60_000L
        return freeMs <= 0 || getUsedMsToday(context, surface) >= freeMs
    }

    /** Local calendar day as a single int (minSdk 23: no java.time). */
    private fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}
