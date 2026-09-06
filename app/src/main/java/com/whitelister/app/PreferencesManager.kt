package com.whitelister.app

import android.content.Context
import android.content.SharedPreferences

object PreferencesManager {
    private const val PREFS_NAME = "whitelister_prefs"
    private const val KEY_REELS_BLOCKING = "reels_blocking_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isReelsBlockingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REELS_BLOCKING, false)

    fun setReelsBlockingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REELS_BLOCKING, enabled).apply()
    }
}
