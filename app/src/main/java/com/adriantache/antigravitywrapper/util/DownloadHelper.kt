package com.adriantache.antigravitywrapper.util

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

data class ParsedDataUri(
    val mimeType: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedDataUri) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class SavedDownload(
    val fileName: String,
    val uri: Uri,
    val mimeType: String
)

sealed class DownloadOutcome {
    data class SavedToDownloads(val saved: SavedDownload) : DownloadOutcome()
    data class EnqueuedInManager(val fileName: String) : DownloadOutcome()
    data class Failed(val message: String) : DownloadOutcome()
}

object DownloadHelper {

    private const val DEFAULT_FILE_STEM = "antigravity-export"
    private const val MAX_FILENAME_LENGTH = 120

    private val EXTENSION_TO_MIME = mapOf(
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "txt" to "text/plain",
        "log" to "text/plain",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "json" to "application/json",
        "jsonl" to "application/json",
        "html" to "text/html",
        "htm" to "text/html",
        "xml" to "application/xml",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "css" to "text/css",
        "ts" to "text/plain",
        "tsx" to "text/plain",
        "jsx" to "text/plain",
        "kt" to "text/plain",
        "kts" to "text/plain",
        "java" to "text/plain",
        "py" to "text/plain",
        "rs" to "text/plain",
        "go" to "text/plain",
        "c" to "text/plain",
        "cpp" to "text/plain",
        "h" to "text/plain",
        "sh" to "text/plain"
    )

    private val MIME_TO_EXTENSION = mapOf(
        "text/markdown" to "md",
        "text/x-markdown" to "md",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "text/tab-separated-values" to "tsv",
        "application/json" to "json",
        "text/html" to "html",
        "application/xml" to "xml",
        "text/xml" to "xml",
        "text/yaml" to "yaml",
        "application/yaml" to "yaml",
        "application/x-yaml" to "yaml",
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/gzip" to "gz",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",
        "audio/mpeg" to "mp3",
        "audio/wav" to "wav",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "text/javascript" to "js",
        "application/javascript" to "js",
        "text/css" to "css"
    )

    private val TEXT_FALLBACK_MIMES = setOf(
        "text/markdown",
        "text/x-markdown",
        "text/csv",
        "text/tab-separated-values",
        "text/yaml",
        "application/yaml",
        "application/x-yaml",
        "application/json",
        "application/xml",
        "text/xml"
    )

    private val RFC5987_FILENAME_REGEX = Regex(
        """filename\*\s*=\s*(?:UTF-8|utf-8)?'[^']*'?([^;\r\n]+)""",
        RegexOption.IGNORE_CASE
    )
    private val QUOTED_FILENAME_REGEX = Regex(
        """filename\s*=\s*"([^"\r\n]+)"""",
        RegexOption.IGNORE_CASE
    )
    private val UNQUOTED_FILENAME_REGEX = Regex(
        """filename\s*=\s*([^;\r\n"]+)""",
        RegexOption.IGNORE_CASE
    )
    private val EXTENSION_REGEX = Regex("""^.+\.([A-Za-z0-9]{1,10})$""")

    fun parseContentDispositionFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null

        val rawMatch = RFC5987_FILENAME_REGEX.find(contentDisposition)?.groupValues?.getOrNull(1)
            ?.trim()?.trim('"')
            ?.let { decodePercentUtf8(it) }
            ?: QUOTED_FILENAME_REGEX.find(contentDisposition)?.groupValues?.getOrNull(1)
            ?: UNQUOTED_FILENAME_REGEX.find(contentDisposition)?.groupValues?.getOrNull(1)

        val cleaned = rawMatch
            ?.trim()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
        return cleaned?.takeIf { it.isNotEmpty() }
    }

    fun sanitizeFileName(rawName: String?, fallback: String = DEFAULT_FILE_STEM): String {
        val stripped = rawName
            .orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .trimStart('.')
            .trim()

        val base = stripped.ifBlank { fallback }
        if (base.length <= MAX_FILENAME_LENGTH) return base

        val dotIdx = base.lastIndexOf('.')
        if (dotIdx > 0 && base.length - dotIdx <= 12) {
            val ext = base.substring(dotIdx)
            val stemMax = (MAX_FILENAME_LENGTH - ext.length).coerceAtLeast(1)
            return base.substring(0, stemMax) + ext
        }
        return base.substring(0, MAX_FILENAME_LENGTH)
    }

    fun normalizeMimeType(mimeType: String?): String {
        return mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    }

    fun inferMimeType(fileName: String, reportedMimeType: String?): String {
        val normalized = normalizeMimeType(reportedMimeType)
        if (normalized.isNotEmpty() &&
            normalized != "application/octet-stream" &&
            normalized != "binary/octet-stream" &&
            normalized.contains('/')
        ) {
            return normalized
        }

        val ext = EXTENSION_REGEX.find(fileName)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
        if (!ext.isNullOrEmpty()) {
            EXTENSION_TO_MIME[ext]?.let { return it }
            val systemMime = runCatching {
                MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
            }.getOrNull()
            if (!systemMime.isNullOrBlank()) return systemMime
        }

        return if (normalized.contains('/')) normalized else "application/octet-stream"
    }

    fun ensureExtension(fileName: String, mimeType: String?): String {
        if (EXTENSION_REGEX.matches(fileName)) return fileName
        val normalized = normalizeMimeType(mimeType)
        val ext = MIME_TO_EXTENSION[normalized]
            ?: runCatching {
                MimeTypeMap.getSingleton()?.getExtensionFromMimeType(normalized)
            }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (!ext.isNullOrBlank()) "$fileName.$ext" else fileName
    }

    fun resolveDownloadFileName(
        url: String?,
        contentDisposition: String?,
        mimeType: String?,
        explicitFileName: String? = null
    ): String {
        val urlCandidate = extractUrlPathFileName(url)
        val candidate = explicitFileName?.takeIf { it.isNotBlank() }
            ?: parseContentDispositionFileName(contentDisposition)
            ?: urlCandidate
            ?: DEFAULT_FILE_STEM

        val sanitized = sanitizeFileName(candidate)
        val resolvedMime = inferMimeType(sanitized, mimeType)
        return ensureExtension(sanitized, resolvedMime)
    }

    private fun extractUrlPathFileName(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val lower = url.trim().lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null

        val withoutFragment = url.substringBefore('#').substringBefore('?')
        val segment = withoutFragment.substringAfterLast('/', "")
        if (segment.isBlank()) return null
        val decoded = decodePercentUtf8(segment).trim()
        return decoded.takeIf { it.isNotEmpty() && it != "/" }
    }

    fun parseDataUri(dataUri: String): ParsedDataUri? {
        val trimmed = dataUri.trim()
        if (!trimmed.startsWith("data:", ignoreCase = true)) return null
        val commaIdx = trimmed.indexOf(',')
        if (commaIdx == -1) return null

        val header = trimmed.substring(5, commaIdx)
        val payload = trimmed.substring(commaIdx + 1)
        val headerParts = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val isBase64 = headerParts.any { it.equals("base64", ignoreCase = true) }
        val mimeType = headerParts
            .firstOrNull { it.contains('/') && !it.equals("base64", ignoreCase = true) }
            ?.lowercase(Locale.ROOT)
            ?: "text/plain"

        val bytes = try {
            if (isBase64) {
                val cleanedPayload = if (payload.contains('%')) {
                    decodePercentUtf8(payload)
                } else {
                    payload
                }
                Base64.getMimeDecoder().decode(cleanedPayload)
            } else {
                decodePercentUtf8(payload).toByteArray(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            return null
        }

        return ParsedDataUri(mimeType = mimeType, bytes = bytes)
    }

    fun decodeBase64(base64Data: String): ByteArray {
        return Base64.getMimeDecoder().decode(base64Data)
    }

    fun saveBytesToDownloads(
        context: Context,
        bytes: ByteArray,
        rawFileName: String?,
        reportedMimeType: String?
    ): SavedDownload {
        val fileName = resolveDownloadFileName(
            url = null,
            contentDisposition = null,
            mimeType = reportedMimeType,
            explicitFileName = rawFileName
        )
        val mimeType = inferMimeType(fileName, reportedMimeType)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create entry in Downloads")

        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: throw IOException("Failed to open output stream for download")

            val publishValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(itemUri, publishValues, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw e
        }

        return SavedDownload(fileName = fileName, uri = itemUri, mimeType = mimeType)
    }

    fun enqueueHttpDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): DownloadOutcome {
        val fileName = resolveDownloadFileName(
            url = url,
            contentDisposition = contentDisposition,
            mimeType = mimeType
        )
        val resolvedMime = inferMimeType(fileName, mimeType)

        return try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Downloading from Antigravity")
                setMimeType(resolvedMime)
                val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookies)
                }
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            DownloadOutcome.EnqueuedInManager(fileName)
        } catch (e: Exception) {
            DownloadOutcome.Failed(e.message ?: "Failed to start download")
        }
    }

    fun openDownloadedFile(context: Context, uri: Uri, mimeType: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(viewIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try plain text viewer for markdown/json/yaml artifacts
        }

        val normalized = normalizeMimeType(mimeType)
        if (normalized in TEXT_FALLBACK_MIMES) {
            val textIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(textIntent)
                return
            } catch (_: ActivityNotFoundException) {
                // Fall through to system Downloads UI
            }
        }

        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(downloadsIntent) }
    }

    private fun decodePercentUtf8(value: String): String {
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }
}
