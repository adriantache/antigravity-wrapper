package com.adriantache.antigravitywrapper

import android.speech.SpeechRecognizer
import com.adriantache.antigravitywrapper.util.VoiceInputHelper
import com.adriantache.antigravitywrapper.util.WebPermissionPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAndPermissionTest {

    @Test
    fun webPermissionPolicy_promptsForAudioCaptureOnlyWhenMissingPermission() {
        val audioOnly = arrayOf(WebPermissionPolicy.RESOURCE_AUDIO_CAPTURE)
        assertTrue(WebPermissionPolicy.requiresRecordAudioPrompt(audioOnly, hasRecordAudioPermission = false))
        assertFalse(WebPermissionPolicy.requiresRecordAudioPrompt(audioOnly, hasRecordAudioPermission = true))

        val videoOnly = arrayOf(WebPermissionPolicy.RESOURCE_VIDEO_CAPTURE)
        assertFalse(WebPermissionPolicy.requiresRecordAudioPrompt(videoOnly, hasRecordAudioPermission = false))

        assertFalse(WebPermissionPolicy.requiresRecordAudioPrompt(emptyArray(), hasRecordAudioPermission = false))
        assertFalse(WebPermissionPolicy.requiresRecordAudioPrompt(null, hasRecordAudioPermission = false))
    }

    @Test
    fun webPermissionPolicy_filtersGrantableResourcesByAndroidPermissions() {
        val mixed = arrayOf(
            WebPermissionPolicy.RESOURCE_AUDIO_CAPTURE,
            WebPermissionPolicy.RESOURCE_VIDEO_CAPTURE,
            "android.webkit.resource.PROTECTED_MEDIA_ID"
        )

        assertArrayEquals(
            arrayOf(
                WebPermissionPolicy.RESOURCE_AUDIO_CAPTURE,
                "android.webkit.resource.PROTECTED_MEDIA_ID"
            ),
            WebPermissionPolicy.resolveGrantableResources(mixed, hasRecordAudioPermission = true)
        )

        assertArrayEquals(
            arrayOf("android.webkit.resource.PROTECTED_MEDIA_ID"),
            WebPermissionPolicy.resolveGrantableResources(mixed, hasRecordAudioPermission = false)
        )

        assertArrayEquals(
            emptyArray<String>(),
            WebPermissionPolicy.resolveGrantableResources(
                arrayOf(WebPermissionPolicy.RESOURCE_AUDIO_CAPTURE),
                hasRecordAudioPermission = false
            )
        )
    }

    @Test
    fun voiceInputHelper_mergesTranscriptCleanlyWithExistingDraft() {
        assertEquals("hello world", VoiceInputHelper.mergeVoiceTranscript("", "  hello world  "))
        assertEquals("Fix the bug", VoiceInputHelper.mergeVoiceTranscript("Fix", "the bug"))
        assertEquals("Fix the bug", VoiceInputHelper.mergeVoiceTranscript("Fix ", "the bug"))
        assertEquals("Line one\nline two", VoiceInputHelper.mergeVoiceTranscript("Line one\n", "line two"))
        assertEquals("Keep draft", VoiceInputHelper.mergeVoiceTranscript("Keep draft", "   "))
    }

    @Test
    fun voiceInputHelper_mapsSpeechErrorsAppropriately() {
        assertEquals(
            "Microphone permission is required for voice input",
            VoiceInputHelper.mapSpeechError(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                userRequestedStop = false,
                hasPartialTranscript = false
            )
        )
        assertEquals(
            "No speech detected",
            VoiceInputHelper.mapSpeechError(
                SpeechRecognizer.ERROR_NO_MATCH,
                userRequestedStop = false,
                hasPartialTranscript = false
            )
        )
        assertNull(
            VoiceInputHelper.mapSpeechError(
                SpeechRecognizer.ERROR_NO_MATCH,
                userRequestedStop = true,
                hasPartialTranscript = false
            )
        )
        assertNull(
            VoiceInputHelper.mapSpeechError(
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                userRequestedStop = false,
                hasPartialTranscript = true
            )
        )
        assertNull(
            VoiceInputHelper.mapSpeechError(
                SpeechRecognizer.ERROR_CLIENT,
                userRequestedStop = true,
                hasPartialTranscript = false
            )
        )
    }
}
