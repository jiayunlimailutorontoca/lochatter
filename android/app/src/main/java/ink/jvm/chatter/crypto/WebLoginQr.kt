package ink.jvm.chatter.crypto

/** Text inside the webpage-login QR: `lochatter-web:<ticketId>.<spki-base64>`. */
object WebLoginQr {
    const val PREFIX = "lochatter-web:"

    data class Code(val id: String, val pub: String)

    fun parse(text: String?): Code? {
        val t = text?.trim() ?: return null
        if (!t.startsWith(PREFIX)) return null
        val rest = t.substring(PREFIX.length)
        val dot = rest.indexOf('.')
        if (dot != 22) return null
        val id = rest.substring(0, 22)
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        val pub = rest.substring(23)
        if (!E2E.isValidPublic(pub)) return null
        return Code(id, pub)
    }
}
