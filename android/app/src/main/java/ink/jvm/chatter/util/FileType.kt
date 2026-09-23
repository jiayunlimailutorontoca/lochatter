package ink.jvm.chatter.util

/**
 * Coarse document type for file bubbles and the file page (icon colour, badge, which inline preview is cheap).
 * The extension wins over the MIME type because pickers often report `application/octet-stream`.
 */
enum class FileType(val label: String, val colorArgb: Long) {
    PDF("PDF", 0xFFE5484D),
    WORD("DOC", 0xFF3B82F6),
    EXCEL("XLS", 0xFF22A06B),
    PPT("PPT", 0xFFF97316),
    ARCHIVE("ZIP", 0xFF8B5CF6),
    APK("APK", 0xFF10B981),
    TEXT("TXT", 0xFF64748B),
    AUDIO("音频", 0xFFEC4899),
    VIDEO("视频", 0xFF0EA5E9),
    IMAGE("图片", 0xFF14B8A6),
    OTHER("文件", 0xFF94A3B8);

    companion object {
        /** Text files up to this size are rendered inline on the file page. */
        const val MAX_TEXT_PREVIEW = 200_000L

        private val WORD_EXT = setOf("doc", "docx", "dot", "dotx", "odt", "rtf", "wps", "pages")
        private val EXCEL_EXT = setOf("xls", "xlsx", "xlsm", "csv", "ods", "et", "numbers")
        private val PPT_EXT = setOf("ppt", "pptx", "pps", "ppsx", "odp", "key", "dps")
        private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "jar")
        private val ARCHIVE_MIME = setOf(
            "application/zip", "application/x-zip-compressed", "application/x-rar-compressed", "application/vnd.rar",
            "application/x-7z-compressed", "application/gzip", "application/x-gzip", "application/x-tar", "application/x-bzip2", "application/x-xz",
        )
        private val TEXT_EXT = setOf(
            "txt", "md", "markdown", "log", "json", "xml", "ini", "yaml", "yml", "toml", "html", "htm", "css",
            "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "cs", "go", "rs", "sh", "bat", "ps1", "sql",
            "conf", "cfg", "properties", "gradle", "srt", "lrc", "vtt",
        )
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "wma", "mid", "midi")
        private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "flv", "wmv", "m4v")
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff", "avif")

        /** Lower-case extension of [name] without the dot; "" when there is none (or it looks like garbage). */
        fun ext(name: String?): String {
            val n = name?.substringAfterLast('/')?.substringAfterLast('\\') ?: return ""
            val dot = n.lastIndexOf('.')
            if (dot <= 0 || dot == n.length - 1) return ""
            val e = n.substring(dot + 1).lowercase()
            return if (e.length <= 8 && e.all { it.isLetterOrDigit() }) e else ""
        }

        fun of(name: String?, mime: String?): FileType {
            val e = ext(name)
            val m = mime.orEmpty().lowercase().substringBefore(';').trim()
            return when {
                e == "pdf" || m == "application/pdf" -> PDF
                e in WORD_EXT || m == "application/msword" || m == "application/rtf" || m.endsWith("wordprocessingml.document") -> WORD
                e in EXCEL_EXT || m == "application/vnd.ms-excel" || m == "text/csv" || m.endsWith("spreadsheetml.sheet") -> EXCEL
                e in PPT_EXT || m == "application/vnd.ms-powerpoint" || m.endsWith("presentationml.presentation") -> PPT
                e in ARCHIVE_EXT || m in ARCHIVE_MIME -> ARCHIVE
                e == "apk" || m == "application/vnd.android.package-archive" -> APK
                e in TEXT_EXT || m.startsWith("text/") || m == "application/json" || m == "application/xml" -> TEXT
                e in AUDIO_EXT || m.startsWith("audio/") -> AUDIO
                e in VIDEO_EXT || m.startsWith("video/") -> VIDEO
                e in IMAGE_EXT || m.startsWith("image/") -> IMAGE
                else -> OTHER
            }
        }

        /** Short badge drawn on the icon: the extension when it is short, otherwise the type label. */
        fun badge(name: String?, mime: String?): String {
            val e = ext(name)
            return if (e.length in 1..4) e.uppercase() else of(name, mime).label
        }

        /** Plain-text files small enough to show inline on the file page. */
        fun isTextPreviewable(name: String?, mime: String?, size: Long): Boolean =
            of(name, mime) == TEXT && size in 1..MAX_TEXT_PREVIEW
    }
}
