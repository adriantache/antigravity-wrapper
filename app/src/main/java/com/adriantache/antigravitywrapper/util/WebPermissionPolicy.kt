package com.adriantache.antigravitywrapper.util

/**
 * Pure policy helper for deciding how Android WebView [android.webkit.PermissionRequest]
 * callbacks map to Android runtime permissions.
 */
object WebPermissionPolicy {

    const val RESOURCE_AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"
    const val RESOURCE_VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE"

    /**
     * Returns true when the WebView request asks for microphone access and the app does
     * not yet hold Android's RECORD_AUDIO runtime permission.
     */
    fun requiresRecordAudioPrompt(
        requestedResources: Array<out String>?,
        hasRecordAudioPermission: Boolean
    ): Boolean {
        if (hasRecordAudioPermission || requestedResources.isNullOrEmpty()) return false
        return requestedResources.any { it == RESOURCE_AUDIO_CAPTURE }
    }

    /**
     * Filters requested WebView permission resources down to those the app is permitted
     * and configured to grant.
     */
    fun resolveGrantableResources(
        requestedResources: Array<out String>?,
        hasRecordAudioPermission: Boolean
    ): Array<String> {
        if (requestedResources.isNullOrEmpty()) return emptyArray()
        return requestedResources.filter { resource ->
            when (resource) {
                RESOURCE_AUDIO_CAPTURE -> hasRecordAudioPermission
                RESOURCE_VIDEO_CAPTURE -> false // CAMERA permission is not declared
                else -> true
            }
        }.toTypedArray()
    }
}
