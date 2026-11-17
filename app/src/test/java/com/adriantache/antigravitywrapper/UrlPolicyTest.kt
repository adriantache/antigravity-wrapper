package com.adriantache.antigravitywrapper

import com.adriantache.antigravitywrapper.util.UrlNavigationAction
import com.adriantache.antigravitywrapper.util.UrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPolicyTest {

    private val defaultServer = "https://antigravity.google"

    @Test
    fun antigravityHosts_stayInWebView() {
        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("https", "antigravity.google", -1, true, defaultServer)
        )
        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("https", "app.antigravity.google", -1, true, defaultServer)
        )
        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("HTTPS", "Antigravity.Google.", -1, true, defaultServer)
        )
    }

    @Test
    fun lookalikeAntigravityHosts_openExternally() {
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("https", "evilantigravity.google", -1, true, defaultServer)
        )
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("https", "antigravity.google.evil.com", -1, true, defaultServer)
        )
    }

    @Test
    fun antigravityScheme_convertsToHttps() {
        assertEquals(
            UrlNavigationAction.CONVERT_ANTIGRAVITY_SCHEME,
            UrlPolicy.classifyNavigation("antigravity", "session", -1, true, defaultServer)
        )
        assertEquals(
            UrlNavigationAction.CONVERT_ANTIGRAVITY_SCHEME,
            UrlPolicy.classifyNavigation("Antigravity", "session", -1, true, defaultServer)
        )
    }

    @Test
    fun googleAuthHosts_stayInWebView() {
        listOf(
            "accounts.google.com",
            "accounts.youtube.com",
            "consent.google.com",
            "consent.youtube.com",
            "gds.google.com"
        ).forEach { authHost ->
            assertEquals(
                "Expected $authHost to stay in WebView",
                UrlNavigationAction.ALLOW_IN_WEBVIEW,
                UrlPolicy.classifyNavigation("https", authHost, -1, true, defaultServer)
            )
        }
    }

    @Test
    fun nonAuthGoogleAndThirdPartyHosts_openExternally() {
        listOf(
            "www.google.com",
            "docs.google.com",
            "drive.google.com",
            "mail.google.com",
            "support.google.com",
            "policies.google.com",
            "myaccount.google.com",
            "developers.google.com",
            "cloud.google.com",
            "notgoogle.com",
            "www.gstatic.com",
            "lh3.googleusercontent.com",
            "github.com",
            "example.com"
        ).forEach { externalHost ->
            assertEquals(
                "Expected $externalHost to open externally",
                UrlNavigationAction.OPEN_EXTERNALLY,
                UrlPolicy.classifyNavigation("https", externalHost, -1, true, defaultServer)
            )
        }
    }

    @Test
    fun localUrls_openExternallyWhenUsingDefaultServer() {
        listOf(
            "localhost" to 3000,
            "10.0.2.2" to 8080,
            "192.168.1.50" to 5173,
            "10.0.0.1" to 80,
            "172.16.0.5" to 8080
        ).forEach { (host, port) ->
            assertEquals(
                "Expected $host:$port to open externally",
                UrlNavigationAction.OPEN_EXTERNALLY,
                UrlPolicy.classifyNavigation("http", host, port, true, defaultServer)
            )
        }
    }

    @Test
    fun configuredCustomServer_allowsMatchingHostAndPortOnly() {
        val customServer = "http://192.168.1.50:8080/workspace"

        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("http", "192.168.1.50", 8080, true, customServer)
        )
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("http", "192.168.1.50", 3000, true, customServer)
        )
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("http", "192.168.1.50", -1, true, customServer)
        )
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("http", "192.168.1.99", 8080, true, customServer)
        )
    }

    @Test
    fun configuredHttpsDomain_matchesDefaultPort() {
        val customDomain = "https://my-antigravity.example.com"

        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("https", "my-antigravity.example.com", -1, true, customDomain)
        )
        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("https", "my-antigravity.example.com", 443, true, customDomain)
        )
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("https", "my-antigravity.example.com", 8443, true, customDomain)
        )
    }

    @Test
    fun configuredIpv6Server_extractsHostAndPort() {
        val ipv6Server = "http://[::1]:8080"

        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("http", "::1", 8080, true, ipv6Server)
        )
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("http", "::1", 3000, true, ipv6Server)
        )
    }

    @Test
    fun subframesAndInternalSchemes_stayInWebView() {
        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("https", "example.com", -1, false, defaultServer)
        )
        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("about", null, -1, true, defaultServer)
        )
        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("blob", null, -1, true, defaultServer)
        )
        assertEquals(
            UrlNavigationAction.ALLOW_IN_WEBVIEW,
            UrlPolicy.classifyNavigation("data", null, -1, true, defaultServer)
        )
    }

    @Test
    fun externalSchemes_openExternally() {
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("mailto", null, -1, true, defaultServer)
        )
        assertEquals(
            UrlNavigationAction.OPEN_EXTERNALLY,
            UrlPolicy.classifyNavigation("tel", null, -1, true, defaultServer)
        )
    }
}
