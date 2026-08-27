package com.whitelister.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object PreferencesManager {
    private const val PREFS_NAME = "whitelister_prefs"
    private const val KEY_REELS_BLOCKING = "reels_blocking_enabled"
    private const val KEY_FEED_FILTERING = "feed_filtering_enabled"
    private const val KEY_WHITELISTED_ACCOUNTS = "whitelisted_accounts"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isReelsBlockingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REELS_BLOCKING, false)
    }

    fun setReelsBlockingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REELS_BLOCKING, enabled).apply()
    }

    fun isFeedFilteringEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FEED_FILTERING, false)
    }

    fun setFeedFilteringEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FEED_FILTERING, enabled).apply()
    }

    fun getWhitelistedAccounts(context: Context): Set<String> {
        val json = getPrefs(context).getString(KEY_WHITELISTED_ACCOUNTS, null) ?: return emptySet()
        val array = JSONArray(json)
        val accounts = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            accounts.add(array.getString(i))
        }
        return accounts
    }

    fun setWhitelistedAccounts(context: Context, accounts: Set<String>) {
        val array = JSONArray()
        accounts.forEach { array.put(it) }
        getPrefs(context).edit().putString(KEY_WHITELISTED_ACCOUNTS, array.toString()).apply()
    }
}
