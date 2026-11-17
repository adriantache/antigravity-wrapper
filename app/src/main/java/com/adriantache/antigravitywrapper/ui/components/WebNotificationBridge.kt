package com.adriantache.antigravitywrapper.ui.components

import android.content.Context
import android.webkit.JavascriptInterface
import com.adriantache.antigravitywrapper.util.NotificationHelper

class WebNotificationBridge(private val context: Context) {

    @JavascriptInterface
    fun postNotification(title: String?, body: String?, targetUrl: String?) {
        NotificationHelper.showNotification(
            context = context,
            title = title ?: "Antigravity",
            body = body ?: "",
            targetUrl = targetUrl
        )
    }

    @JavascriptInterface
    fun getPermission(): String {
        return "granted"
    }
}
