package com.whitelister.app

import android.content.Context
import android.content.SharedPreferences

object PreferencesManager {
    private const val PREFS_NAME = "whitelister_prefs"
    private const val KEY_REELS_BLOCKING = "reels_blocking_enabled"
    private const val KEY_BLOCK_HOME_FEED = "block_home_feed_enabled"
    private const val KEY_CONSENT_ACCEPTED = "consent_accepted"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isReelsBlockingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REELS_BLOCKING, false)
    }

    fun setReelsBlockingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REELS_BLOCKING, enabled).apply()
    }

    fun isConsentAccepted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CONSENT_ACCEPTED, false)
    }

    fun setConsentAccepted(context: Context, accepted: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CONSENT_ACCEPTED, accepted).apply()
    }

    fun isBlockHomeFeedEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BLOCK_HOME_FEED, false)
    }

    fun setBlockHomeFeedEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BLOCK_HOME_FEED, enabled).apply()
    }
}