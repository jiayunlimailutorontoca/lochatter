package ink.jvm.chatter

import ink.jvm.chatter.util.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTypeTest {
    @Test
    fun extension_wins_over_mime() {
        assertEquals(FileType.PDF, FileType.of("合同.PDF", "application/octet-stream"))
        assertEquals(FileType.WORD, FileType.of("简历.docx", "application/octet-stream"))
        assertEquals(FileType.EXCEL, FileType.of("账本.xlsx", "application/octet-stream"))
        assertEquals(FileType.PPT, FileType.of("汇报.pptx", "application/octet-stream"))
        assertEquals(FileType.ARCHIVE, FileType.of("photos.zip", "application/octet-stream"))
        assertEquals(FileType.ARCHIVE, FileType.of("backup.tar.gz", null))
        assertEquals(FileType.APK, FileType.of("lochatter.apk", "application/octet-stream"))
        assertEquals(FileType.TEXT, FileType.of("notes.md", "application/octet-stream"))
        assertEquals(FileType.AUDIO, FileType.of("song.flac", "application/octet-stream"))
        assertEquals(FileType.VIDEO, FileType.of("clip.mkv", "application/octet-stream"))
        assertEquals(FileType.IMAGE, FileType.of("scan.heic", "application/octet-stream"))
    }

    @Test
    fun mime_decides_without_a_usable_extension() {
        assertEquals(FileType.PDF, FileType.of(null, "application/pdf"))
        assertEquals(FileType.IMAGE, FileType.of("photo", "image/jpeg"))
        assertEquals(FileType.VIDEO, FileType.of("", "video/mp4"))
        assertEquals(FileType.AUDIO, FileType.of("voice", "audio/mp4"))
        assertEquals(FileType.WORD, FileType.of("x", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(FileType.EXCEL, FileType.of("x", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals(FileType.PPT, FileType.of("x", "application/vnd.ms-powerpoint"))
        assertEquals(FileType.ARCHIVE, FileType.of("x", "application/x-7z-compressed"))
        assertEquals(FileType.APK, FileType.of("x", "application/vnd.android.package-archive"))
        assertEquals(FileType.TEXT, FileType.of("x", "text/plain; charset=utf-8"))
        assertEquals(FileType.TEXT, FileType.of("x", "application/json"))
    }

    @Test
    fun unknown_is_other() {
        assertEquals(FileType.OTHER, FileType.of("blob", "application/octet-stream"))
        assertEquals(FileType.OTHER, FileType.of(null, null))
        assertEquals(FileType.OTHER, FileType.of("weird.q1w2e3r4t5", ""))
    }

    @Test
    fun extension_helper() {
        assertEquals("gz", FileType.ext("backup.tar.gz"))
        assertEquals("txt", FileType.ext("dir/sub/Notes.TXT"))
        assertEquals("", FileType.ext(".bashrc"))
        assertEquals("", FileType.ext("noext"))
        assertEquals("", FileType.ext("trailing."))
        assertEquals("", FileType.ext(null))
        assertEquals("", FileType.ext("a.b c"))
    }

    @Test
    fun badge_prefers_a_short_extension() {
        assertEquals("DOCX", FileType.badge("a.docx", null))
        assertEquals("TXT", FileType.badge("a.txt", "text/plain"))
        assertEquals("PDF", FileType.badge(null, "application/pdf"))
        assertEquals("文件", FileType.badge("a.longext", "application/octet-stream"))
        assertEquals("图片", FileType.badge("photo", "image/png"))
    }

    @Test
    fun text_preview_only_for_small_text_files() {
        assertTrue(FileType.isTextPreviewable("a.txt", "text/plain", 1_000))
        assertTrue(FileType.isTextPreviewable("a.json", null, FileType.MAX_TEXT_PREVIEW))
        assertFalse(FileType.isTextPreviewable("a.txt", "text/plain", FileType.MAX_TEXT_PREVIEW + 1))
        assertFalse(FileType.isTextPreviewable("a.txt", "text/plain", 0))
        assertFalse(FileType.isTextPreviewable("a.pdf", "application/pdf", 1_000))
    }
}
