package ink.jvm.chatter.data

/** Short plaintext the server may hand to Server酱 or MeoW when the other person has no connection. */
object PushNotice {
    fun of(kind: String, plain: String?, once: Boolean = false): String? {
        if (once) return "阅后即焚"
        val p = plain?.trim().orEmpty()
        val body = when (kind) {
            "text", "card" -> p
            "image", "album" -> if (p.isEmpty()) "[图片]" else "[图片] ${p.take(80)}"
            "audio" -> "[语音]"
            "video" -> if (p.isEmpty()) "[视频]" else "[视频] ${p.take(80)}"
            "file" -> if (p.isEmpty()) "[文件]" else "[文件] ${p.take(80)}"
            "sticker" -> "[表情]"
            "pat" -> "拍了拍你"
            "location" -> {
                val addr = p.split('|').getOrNull(2)?.trim().orEmpty()
                if (addr.isEmpty()) "[位置]" else "[位置] ${addr.take(60)}"
            }
            "call" -> if (p.isEmpty()) "[通话]" else p.take(80)
            else -> return null
        }
        return body.take(400).ifEmpty { null }
    }
}
