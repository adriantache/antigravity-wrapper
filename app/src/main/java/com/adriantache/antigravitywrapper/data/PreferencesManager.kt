package com.adriantache.antigravitywrapper.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("antigravity_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_RECENT_URLS = "recent_urls"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_PULL_TO_REFRESH = "pull_to_refresh"
        private const val KEY_DOUBLE_BACK_EXIT = "double_back_exit"

        const val DEFAULT_URL = "https://antigravity.google"
    }

    var serverUrl: String
        get() {
            val saved = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
            return if (saved == "http://10.0.2.2:8080" || saved == "http://localhost:8080") {
                DEFAULT_URL
            } else {
                saved
            }
        }
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
            addRecentUrl(value)
        }

    val recentUrls: List<String>
        get() {
            val raw = prefs.getString(KEY_RECENT_URLS, "") ?: ""
            return if (raw.isBlank()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
        }

    fun addRecentUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val current = recentUrls.toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val limited = current.take(10)
        prefs.edit().putString(KEY_RECENT_URLS, limited.joinToString("\n")).apply()
    }

    fun clearRecentUrls() {
        prefs.edit().remove(KEY_RECENT_URLS).apply()
    }

    var isDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()

    var isKeepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var isPullToRefreshEnabled: Boolean
        get() = prefs.getBoolean(KEY_PULL_TO_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_PULL_TO_REFRESH, value).apply()

    var isDoubleBackExitEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_BACK_EXIT, true)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_BACK_EXIT, value).apply()
}
