package com.whitelister.app

import android.content.Context
import android.content.SharedPreferences

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

    /** Free minutes before a surface's block engages: "<surface>_free_minutes". */
    private const val SUFFIX_FREE_MINUTES = "_free_minutes"
    /**
     * Milliseconds spent on a surface since its free time was last refilled. (Older versions
     * kept a per-day counter under "_used_ms"/"_used_day"; new keys so it starts clean.)
     */
    private const val SUFFIX_USED_MS = "_period_used_ms"
    /** Epoch millis at which the surface's free time ran out and its block started (0 = not). */
    private const val SUFFIX_BLOCKED_AT = "_blocked_at"

    /**
     * How long, in hours (1..24, default 24), a block lasts once the free time has run out. The
     * timer starts at the moment the free time is used up; when it ends the free time is full
     * again. Minutes left unused do not expire.
     */
    private const val KEY_WINDOW_HOURS = "free_window_hours"
    const val DEFAULT_WINDOW_HOURS = 24
    const val MIN_WINDOW_HOURS = 1
    const val MAX_WINDOW_HOURS = 24

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

    fun getWindowHours(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW_HOURS, DEFAULT_WINDOW_HOURS)
            .coerceIn(MIN_WINDOW_HOURS, MAX_WINDOW_HOURS)

    fun setWindowHours(context: Context, hours: Int) {
        prefs(context).edit()
            .putInt(KEY_WINDOW_HOURS, hours.coerceIn(MIN_WINDOW_HOURS, MAX_WINDOW_HOURS))
            .apply()
    }

    /**
     * Milliseconds of [surface]'s free time used so far. Back to 0 once the block that followed
     * the free time running out has lasted [getWindowHours].
     */
    fun getUsedMsToday(context: Context, surface: IgSurface): Long = currentUsedMs(context, surface)

    fun addUsedMs(context: Context, surface: IgSurface, ms: Long) {
        if (ms <= 0) return
        val before = currentUsedMs(context, surface)
        val used = before + ms
        val edit = prefs(context).edit().putLong(surface.key + SUFFIX_USED_MS, used)
        val freeMs = freeMs(context, surface)
        if (freeMs > 0 && before < freeMs && used >= freeMs) {
            // The free time just ran out: the block, and its timer, start now.
            edit.putLong(surface.key + SUFFIX_BLOCKED_AT, System.currentTimeMillis())
        }
        edit.apply()
    }

    /**
     * Epoch millis at which [surface]'s current block ends and its free time is full again, or 0
     * when it is not in a timed block (free time left, or no free time = always blocked).
     */
    fun blockEndsAtMillis(context: Context, surface: IgSurface): Long {
        val freeMs = freeMs(context, surface)
        if (freeMs <= 0 || currentUsedMs(context, surface) < freeMs) return 0L
        val blockedAt = prefs(context).getLong(surface.key + SUFFIX_BLOCKED_AT, 0L)
        return if (blockedAt > 0) blockedAt + windowMs(context) else 0L
    }

    /**
     * True when [surface]'s block should act right now: the feature is on, its free time (if
     * any) is used up and the block that followed has not lasted [getWindowHours] yet.
     */
    fun shouldBlock(context: Context, surface: IgSurface): Boolean {
        if (!isEnabled(context, surface)) return false
        val freeMs = freeMs(context, surface)
        return freeMs <= 0 || currentUsedMs(context, surface) >= freeMs
    }

    private fun freeMs(context: Context, surface: IgSurface): Long =
        getFreeMinutes(context, surface) * 60_000L

    private fun windowMs(context: Context): Long = getWindowHours(context) * 3_600_000L

    /**
     * Used milliseconds, refilling the free time when the block timer has run out. A surface
     * found over its free time without a timer (e.g. the free minutes were lowered below what
     * was already used) starts its timer now, so it can never stay blocked forever.
     */
    private fun currentUsedMs(context: Context, surface: IgSurface): Long {
        val p = prefs(context)
        val used = p.getLong(surface.key + SUFFIX_USED_MS, 0L)
        val freeMs = freeMs(context, surface)
        if (freeMs <= 0 || used < freeMs) return used
        val blockedAt = p.getLong(surface.key + SUFFIX_BLOCKED_AT, 0L)
        val now = System.currentTimeMillis()
        if (blockedAt <= 0 || blockedAt > now) {
            p.edit().putLong(surface.key + SUFFIX_BLOCKED_AT, now).apply()
            return used
        }
        if (now - blockedAt >= windowMs(context)) {
            p.edit()
                .putLong(surface.key + SUFFIX_USED_MS, 0L)
                .putLong(surface.key + SUFFIX_BLOCKED_AT, 0L)
                .apply()
            return 0L
        }
        return used
    }
}
