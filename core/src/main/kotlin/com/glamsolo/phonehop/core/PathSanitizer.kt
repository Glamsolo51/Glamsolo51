package com.glamsolo.phonehop.core

/**
 * Makes names that come from the network safe to use on the local file system.
 *
 * A malicious or buggy sender must never be able to write outside the target
 * folder ("../../data"), create hidden or reserved names, or use characters that
 * Android's storage providers reject.
 */
object PathSanitizer {
    private const val MAX_SEGMENT_LENGTH = 120
    private const val MAX_DEPTH = 16
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val APK_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.apk$")

    /** Sanitizes one path segment. Returns null if nothing usable is left. */
    fun sanitizeSegment(raw: String): String? {
        var s = raw.replace(ILLEGAL, "_").trim()
        // Leading dots would create hidden files or the special "." / ".." entries.
        s = s.trimStart('.').trimEnd('.', ' ')
        if (s.isEmpty() || s.all { it == '_' }) return null
        if (s.length > MAX_SEGMENT_LENGTH) {
            val dot = s.lastIndexOf('.')
            val ext = if (dot > 0 && s.length - dot <= 16) s.substring(dot) else ""
            s = s.take(MAX_SEGMENT_LENGTH - ext.length).trimEnd('.', ' ') + ext
        }
        return s
    }

    /**
     * Sanitizes a relative path such as "Photos/Trip/IMG_1.jpg".
     * Both '/' and '\' are treated as separators; ".", ".." and empty segments are dropped.
     */
    fun sanitizeRelativePath(raw: String, fallbackName: String = "file"): String {
        val segments = raw.split('/', '\\')
            .filter { it != "." && it != ".." }
            .mapNotNull { sanitizeSegment(it) }
        if (segments.isEmpty()) return sanitizeSegment(fallbackName) ?: "file"
        // Keep the file name and the innermost folders if the path is too deep.
        return segments.takeLast(MAX_DEPTH).joinToString("/")
    }

    fun isValidPackageName(name: String): Boolean = name.length <= 255 && PACKAGE_NAME.matches(name)

    /** APK part names come from the sender too: only allow plain "*.apk" names. */
    fun sanitizeApkName(raw: String, index: Int): String =
        if (APK_NAME.matches(raw) && !raw.contains("..")) raw else "part_$index.apk"
}
