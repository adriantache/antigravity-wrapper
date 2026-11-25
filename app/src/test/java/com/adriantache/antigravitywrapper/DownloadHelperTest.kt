package com.adriantache.antigravitywrapper

import com.adriantache.antigravitywrapper.util.DownloadHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadHelperTest {

    @Test
    fun parseContentDispositionFileName_handlesQuotedUnquotedAndRfc5987() {
        assertEquals(
            "implementation_plan.md",
            DownloadHelper.parseContentDispositionFileName("""attachment; filename="implementation_plan.md"""")
        )
        assertEquals(
            "walkthrough.md",
            DownloadHelper.parseContentDispositionFileName("attachment; filename=walkthrough.md; size=1024")
        )
        assertEquals(
            "my plan+v2.md",
            DownloadHelper.parseContentDispositionFileName("attachment; filename*=UTF-8''my%20plan+v2.md")
        )
        assertEquals(
            "report.json",
            DownloadHelper.parseContentDispositionFileName("""attachment; filename="fallback.json"; filename*=UTF-8''report.json""")
        )
        assertEquals(
            "secret.txt",
            DownloadHelper.parseContentDispositionFileName("""attachment; filename="../../secret.txt"""")
        )
        assertNull(DownloadHelper.parseContentDispositionFileName("inline"))
        assertNull(DownloadHelper.parseContentDispositionFileName(null))
    }

    @Test
    fun sanitizeFileName_cleansIllegalCharsAndPreservesExtension() {
        assertEquals(
            "plan_2026_v1.md",
            DownloadHelper.sanitizeFileName("plan:2026*v1.md")
        )
        assertEquals(
            "env",
            DownloadHelper.sanitizeFileName("..env")
        )
        assertEquals(
            "antigravity-export",
            DownloadHelper.sanitizeFileName("   ///   ")
        )

        val longName = "a".repeat(140) + ".markdown"
        val sanitized = DownloadHelper.sanitizeFileName(longName)
        assertEquals(120, sanitized.length)
        assertEquals(".markdown", sanitized.substring(sanitized.length - 9))
    }

    @Test
    fun resolveDownloadFileName_prefersExplicitThenHeaderThenUrlAndEnsuresExtension() {
        assertEquals(
            "artifact.md",
            DownloadHelper.resolveDownloadFileName(
                url = "https://antigravity.google.com/api/export?id=123",
                contentDisposition = """attachment; filename="wrong.txt"""",
                mimeType = "text/markdown",
                explicitFileName = "artifact.md"
            )
        )

        assertEquals(
            "export-notes.md",
            DownloadHelper.resolveDownloadFileName(
                url = "https://antigravity.google.com/api/export",
                contentDisposition = """attachment; filename="export-notes"""",
                mimeType = "text/markdown"
            )
        )

        assertEquals(
            "bundle.zip",
            DownloadHelper.resolveDownloadFileName(
                url = "https://antigravity.google.com/files/bundle.zip?token=abc",
                contentDisposition = null,
                mimeType = "application/zip"
            )
        )

        assertEquals(
            "antigravity-export.json",
            DownloadHelper.resolveDownloadFileName(
                url = "blob:https://antigravity.static.usercontent.goog/1234",
                contentDisposition = null,
                mimeType = "application/json"
            )
        )
    }

    @Test
    fun inferMimeType_fallsBackToExtensionWhenGeneric() {
        assertEquals("text/markdown", DownloadHelper.inferMimeType("plan.md", "application/octet-stream"))
        assertEquals("text/markdown", DownloadHelper.inferMimeType("plan.md", ""))
        assertEquals("application/json", DownloadHelper.inferMimeType("data.json", null))
        assertEquals("image/png", DownloadHelper.inferMimeType("preview.png", "image/png; charset=utf-8"))
    }

    @Test
    fun parseDataUri_decodesBase64AndUtf8Payloads() {
        val b64Parsed = DownloadHelper.parseDataUri("data:text/markdown;base64,IyBIZWxsbw==")
        assertNotNull(b64Parsed)
        assertEquals("text/markdown", b64Parsed!!.mimeType)
        assertEquals("# Hello", b64Parsed.bytes.toString(Charsets.UTF_8))

        val textParsed = DownloadHelper.parseDataUri("data:text/plain;charset=utf-8,A%2BB%20C%21")
        assertNotNull(textParsed)
        assertEquals("text/plain", textParsed!!.mimeType)
        assertArrayEquals("A+B C!".toByteArray(Charsets.UTF_8), textParsed.bytes)

        assertNull(DownloadHelper.parseDataUri("https://example.com/file.md"))
        assertNull(DownloadHelper.parseDataUri("data:text/plain;base64"))
    }
}
