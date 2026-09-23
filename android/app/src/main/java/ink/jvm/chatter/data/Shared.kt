package ink.jvm.chatter.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** A date the two of you count towards. [date] is yyyy-MM-dd; yearly ones repeat every year. */
@Serializable
data class Anniversary(val id: String, val title: String, val date: String, val yearly: Boolean = true) {
    /** Days until the next occurrence (0 = today); negative only for one-off dates already past. */
    fun daysLeft(today: java.time.LocalDate = java.time.LocalDate.now()): Long {
        val d = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return Long.MAX_VALUE
        if (!yearly) return java.time.temporal.ChronoUnit.DAYS.between(today, d)
        var next = d.withYear(today.year)
        if (next.isBefore(today)) next = next.plusYears(1)
        return java.time.temporal.ChronoUnit.DAYS.between(today, next)
    }

    /** How many years it has been on the next occurrence (0 for the first time or one-off dates). */
    fun years(today: java.time.LocalDate = java.time.LocalDate.now()): Int {
        val d = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return 0
        if (!yearly) return 0
        var next = d.withYear(today.year)
        if (next.isBefore(today)) next = next.plusYears(1)
        return (next.year - d.year).coerceAtLeast(0)
    }
}

/** JSON codecs for the values kept in the shared store. */
object SharedCodec {
    const val KEY_QUICK = "quick"
    const val KEY_ANNIV = "anniv"
    const val KEY_STICKERS = "stickers"
    /** "1" when the human conversation is pinned for both phones. */
    const val KEY_PIN = "pin"

    /** This person's own avatar media id. Only that account may write it. */
    fun avatarKey(userId: Long) = "av-$userId"

    /** This person's own signature. Only that account may write it. */
    fun signKey(userId: Long) = "sg-$userId"

    /** Default quick commands for the assistant page (the household ones from the plan). */
    val DEFAULT_QUICK = listOf("家里现在什么状况？", "全屋关灯关窗帘", "今天和明天的天气", "让客厅小爱说：")

    fun strings(json: String?): List<String> = runCatching { ProtoJson.decodeFromString(ListSerializer(String.serializer()), json ?: "[]") }.getOrDefault(emptyList())
    fun strings(list: List<String>): String = ProtoJson.encodeToString(ListSerializer(String.serializer()), list)

    fun anniversaries(json: String?): List<Anniversary> = runCatching { ProtoJson.decodeFromString(ListSerializer(Anniversary.serializer()), json ?: "[]") }.getOrDefault(emptyList())
    fun anniversaries(list: List<Anniversary>): String = ProtoJson.encodeToString(ListSerializer(Anniversary.serializer()), list)

    /** Custom sticker favourites travel as their encoded refs. */
    fun stickers(json: String?): List<StickerRef> = strings(json).mapNotNull { StickerRef.parse(it) }
    fun stickers(list: List<StickerRef>): String = strings(list.map { it.encode() })
}
