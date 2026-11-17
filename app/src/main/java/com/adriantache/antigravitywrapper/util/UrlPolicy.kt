package com.adriantache.antigravitywrapper.util

enum class UrlNavigationAction {
    ALLOW_IN_WEBVIEW,
    CONVERT_ANTIGRAVITY_SCHEME,
    OPEN_EXTERNALLY
}

object UrlPolicy {

    private val INTERNAL_WEBVIEW_SCHEMES = setOf("about", "blob", "data", "javascript")

    private val GOOGLE_AUTH_HOSTS = setOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "consent.google.com",
        "consent.youtube.com",
        "gds.google.com"
    )

    /**
     * Classifies a WebView navigation request to determine whether it belongs inside the
     * Antigravity WebView or should be handed off to an external browser/app.
     */
    fun classifyNavigation(
        scheme: String?,
        host: String?,
        port: Int,
        isForMainFrame: Boolean,
        configuredServerUrl: String
    ): UrlNavigationAction {
        val normalizedScheme = scheme?.lowercase().orEmpty()

        if (normalizedScheme == "antigravity") {
            return UrlNavigationAction.CONVERT_ANTIGRAVITY_SCHEME
        }

        if (!isForMainFrame || normalizedScheme in INTERNAL_WEBVIEW_SCHEMES) {
            return UrlNavigationAction.ALLOW_IN_WEBVIEW
        }

        if (normalizedScheme != "http" && normalizedScheme != "https") {
            return UrlNavigationAction.OPEN_EXTERNALLY
        }

        val normalizedHost = host?.lowercase()?.trimEnd('.').orEmpty()
        if (normalizedHost.isEmpty()) {
            return UrlNavigationAction.OPEN_EXTERNALLY
        }

        if (isAntigravityHost(normalizedHost) || normalizedHost in GOOGLE_AUTH_HOSTS) {
            return UrlNavigationAction.ALLOW_IN_WEBVIEW
        }

        if (matchesConfiguredServer(normalizedScheme, normalizedHost, port, configuredServerUrl)) {
            return UrlNavigationAction.ALLOW_IN_WEBVIEW
        }

        return UrlNavigationAction.OPEN_EXTERNALLY
    }

    private fun isAntigravityHost(host: String): Boolean {
        return host == "antigravity.google" || host.endsWith(".antigravity.google")
    }

    private fun matchesConfiguredServer(
        requestScheme: String,
        requestHost: String,
        requestExplicitPort: Int,
        configuredServerUrl: String
    ): Boolean {
        val configured = extractEndpoint(configuredServerUrl) ?: return false
        if (requestHost != configured.host) return false

        if (configured.explicitPort == -1) {
            return requestExplicitPort == -1 ||
                requestExplicitPort == effectivePort(requestScheme, -1)
        }

        val requestEffectivePort = effectivePort(requestScheme, requestExplicitPort)
        val configuredEffectivePort = effectivePort(configured.scheme, configured.explicitPort)
        return requestEffectivePort == configuredEffectivePort
    }

    private fun effectivePort(scheme: String, explicitPort: Int): Int {
        if (explicitPort != -1) return explicitPort
        return if (scheme == "https") 443 else 80
    }

    internal data class Endpoint(
        val scheme: String,
        val host: String,
        val explicitPort: Int
    )

    internal fun extractEndpoint(rawUrl: String): Endpoint? {
        val trimmed = rawUrl.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        val afterScheme = trimmed.substring(schemeEnd + 3)
        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let {
            if (it == -1) afterScheme.length else it
        }
        var authority = afterScheme.substring(0, authorityEnd)
        val atIdx = authority.lastIndexOf('@')
        if (atIdx != -1) {
            authority = authority.substring(atIdx + 1)
        }
        if (authority.isEmpty()) return null

        val host: String
        val explicitPort: Int
        if (authority.startsWith("[")) {
            val closeBracket = authority.indexOf(']')
            if (closeBracket == -1) return null
            host = authority.substring(1, closeBracket).lowercase()
            val rest = authority.substring(closeBracket + 1)
            explicitPort = when {
                rest.isEmpty() -> -1
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
        } else {
            val colonIdx = authority.lastIndexOf(':')
            if (colonIdx != -1) {
                host = authority.substring(0, colonIdx).lowercase().trimEnd('.')
                explicitPort = authority.substring(colonIdx + 1).toIntOrNull() ?: return null
            } else {
                host = authority.lowercase().trimEnd('.')
                explicitPort = -1
            }
        }

        if (host.isEmpty()) return null
        return Endpoint(scheme = scheme, host = host, explicitPort = explicitPort)
    }
}
