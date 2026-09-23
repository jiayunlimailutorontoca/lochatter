package ink.jvm.chatter.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Full emoji picker for reactions: a grid of curated emoji under category headers, with the recently used ones
 * first. Every entry is one grapheme with no skin tone, so it fits a reaction chip. Tapping one calls [onPick]
 * then [onClose]; the caller records it with [EmojiRecents.touch].
 */
@Composable
fun EmojiPickerDialog(recent: List<String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    val sections = remember(recent) {
        val r = recent.filter { it.isNotBlank() }.distinct().take(EmojiRecents.MAX)
        if (r.isEmpty()) EMOJI_SECTIONS else listOf(EmojiSection("最近", "🕒", r)) + EMOJI_SECTIONS
    }
    // Grid index of each section header (one header item, then the section's emoji).
    val headerAt = remember(sections) {
        var next = 0
        sections.map { s -> next.also { next += 1 + s.emoji.size } }
    }
    val grid = rememberLazyGridState()
    val current by remember(headerAt) { derivedStateOf { headerAt.indexOfLast { it <= grid.firstVisibleItemIndex }.coerceAtLeast(0) } }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp).fillMaxWidth().fillMaxHeight(0.78f),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("选择表情", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "关闭") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    sections.forEachIndexed { i, s ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(CircleShape)
                                .background(if (i == current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { scope.launch { grid.animateScrollToItem(headerAt[i]) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(s.icon, fontSize = 18.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(44.dp),
                    state = grid,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    sections.forEach { s ->
                        item(key = "h:" + s.title, contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                s.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(s.emoji, key = { s.title + ":" + it }, contentType = { "emoji" }) { e ->
                            Box(
                                Modifier
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .clickable { onPick(e); onClose() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(e, fontSize = 24.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Recently picked emoji, most recent first, in SharedPreferences "emoji". */
object EmojiRecents {
    const val MAX = 24
    private const val FILE = "emoji"
    private const val KEY = "recent"

    fun get(ctx: Context): List<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split('\n')?.filter { it.isNotBlank() }?.distinct()?.take(MAX) ?: emptyList()

    fun touch(ctx: Context, emoji: String) {
        if (emoji.isBlank() || '\n' in emoji) return
        val next = (listOf(emoji) + get(ctx).filter { it != emoji }).take(MAX)
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString("\n")).apply()
    }
}

private class EmojiSection(val title: String, val icon: String, val emoji: List<String>)

private val WS = Regex("\\s+")

private fun emoji(block: String): List<String> = block.trim().split(WS)

/** Curated, skin-tone-neutral, one grapheme each; Emoji 11 and older (Android 9 fonts; older phones get them via Compose's EmojiCompat). */
private val EMOJI_SECTIONS: List<EmojiSection> = listOf(
    EmojiSection("表情", "😀", emoji("""
        😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐
        🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐
        😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 😤 😡 😠 🤬 😈 👿 💀 💩
        🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊
    """)),
    EmojiSection("手势", "👍", emoji("""
        👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👋 🤚 ✋ 🖖 👈 👉 👆 👇 ☝️ ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 👀
        🙋 🙆 🙅 🙇 🤦 🤷 💁 🙎 🙍 💃 🕺 🧘 🛌 👫 💑 💏
    """)),
    EmojiSection("爱心", "❤️", emoji("""
        ❤️ 🧡 💛 💚 💙 💜 🖤 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💋 💌 💍 💎 🎀 🌹 🌷 🌸 💐 🥀 🌺 🌻 🌼
        🍀 🧸 🎁 🍫 🍬 🍭 🎂 ✨ 💫 🌟 🌈 🥂
    """)),
    EmojiSection("动物", "🐶", emoji("""
        🐶 🐱 🐭 🐹 🐰 🦊 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦉 🐺 🐴 🦄 🐝 🐛 🦋 🐌 🐢 🐍 🐙
        🦀 🐠 🐬 🐳 🦈 🐘 🦒 🐑 🐕 🐈 🐇 🐾 🐉 🌵 🎄 🌲 🌴 🌱 🌿 🍁 🍂 🍃 🌞 🌝 🌙 ⭐ ☀️ ⛅ ☁️ 🌧️
        ❄️ ⛄ ☔ ⚡ 🔥 💧 🌊 🌍
    """)),
    EmojiSection("食物", "🍔", emoji("""
        🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🍍 🥝 🍅 🥑 🥕 🌽 🥦 🍄 🍞 🥐 🧀 🍳 🥞 🥓 🍗 🌭 🍔 🍟 🍕 🌮
        🥗 🍜 🍝 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🥮 🍡 🍧 🍨 🍦 🍰 🍩 🍪 🍿 🥛 ☕ 🍵 🥤 🍶 🍺 🍻 🍷 🍸 🍹
        🥢 🍽️
    """)),
    EmojiSection("活动", "⚽", emoji("""
        ⚽ 🏀 🏈 ⚾ 🎾 🏐 🎱 🏓 🏸 🥊 ⛳ 🎣 🎿 🏊 🚴 🎯 🎮 🎲 🧩 🎳 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎸 🎻
        🏆 🥇 🥈 🥉 🏅 🎉 🎊 🎈 🧧 🎃 🎆 🎇 🧨 🛍️
    """)),
    EmojiSection("物品", "📱", emoji("""
        📱 💻 ⌚ 📷 📹 📞 📺 ⏰ ⏳ 🔋 💡 🔦 🕯️ 💸 💰 💳 🔧 🔨 🔑 🚪 🛏️ 🛒 📦 ✉️ 📝 📖 📚 📎 📌 📍
        ✂️ 🔒 🔓 👓 👔 👕 👗 👑 🎩 🎓 🧢 💄 🌂 🎒 👟 🚗 🚕 🚌 🚲 ✈️ 🚀 ⛵ 🚂 🏠 🏥 🏫 🏰 🗼 🏖️ 🏔️
    """)),
    EmojiSection("符号", "✅", emoji("""
        ✅ ❌ ❓ ❗ ‼️ 💯 🆗 🆒 🆕 🆙 🆓 ⭕ ✔️ ✖️ ➕ ➖ 🔴 🔵 ⚫ ⚪ 🔔 🔕 🎵 🎶 ➡️ ⬅️ ⬆️ ⬇️ 🔄 💤
        💢 💬 💭 ♻️ ⚠️ 🚫 ☯️ 🈶 🈚 ㊙️ ㊗️ 🉐 🈲 🈵 🔟 ♾️
    """)),
    EmojiSection("更多表情", "🥲", emoji("""
        🥲 ☺️ 😆 😅 🤣 😂 🥹 😋 😜 🤪 😝 🤑 🤗 🤭 🫢 🫣 🤫 🤔 🫡 🤐 🤨 😐 😑 😶 🫥 😏 😒 🙄 😬
        😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 😵‍💫 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 🫤 😟
        🙁 ☹️ 😮 😯 😲 😳 🥺 🥹 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿
        💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾
    """)),
    EmojiSection("更多手势", "🫶", emoji("""
        🫶 👍 👎 👊 ✊ 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦿 🦵 🦶 👂 🦻 👃 🧠 🦷 🦴 👀 👁️
        👅 👄 💋 👶 🧒 👦 👧 🧑 👱 👨 👩 🧔 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵️ 💂 🥷
        👷 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🧍 🧎 🏃
        💃 🕺 🕴️ 👯 🧖 🧗 🤸 🏌️ 🏇 ⛷️ 🏂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🧘
    """)),
    EmojiSection("自然", "🌸", emoji("""
        🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅
        🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷️ 🕸️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑
        🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🦧 🦣 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬 🐃 🐂 🐄
        🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🦮 🐈 🪶 🐓 🦃 🦤 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁
        🐀 🐿️ 🦔 🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🍄 🐚 🪨 🌾 💐 🌷 🌹 🥀 🌺
        🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥
        🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 ☔ ☂️ 🌊 🌫️
    """)),
    EmojiSection("吃的", "🍜", emoji("""
        🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒
        🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🦴 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆
        🌮 🌯 🫔 🥗 🥘 🫕 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧
        🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 🫖 ☕ 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🥃 🍸
        🍹 🧉 🍾 🧊 🥄 🍴 🍽️ 🥣 🥡 🥢 🧂
    """)),
    EmojiSection("出行", "✈️", emoji("""
        🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🚡
        🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️
        🛳️ ⛴️ 🚢 ⚓ 🪝 ⛽ 🚧 🚦 🚥 🚏 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️
        🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🛕 🕋
        ⛩️ 🛤️ 🛣️ 🗾 🎑 🏞️ 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙️ 🌃 🌌 🌉 🌁
    """)),
    EmojiSection("物件", "🎁", emoji("""
        ⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🖲️ 🕹️ 🗜️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺
        📻 🎙️ 🎚️ 🎛️ 🧭 ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 🪔 🧯 🛢️ 💸 💵 💴 💶 💷 🪙 💰
        💳 💎 ⚖️ 🪜 🧰 🔧 🔨 ⚒️ 🛠️ ⛏️ 🪚 🔩 ⚙️ 🪤 🧱 ⛓️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️
        🪦 ⚱️ 🏺 🔮 📿 🧿 💈 ⚗️ 🔭 🔬 🕳️ 🩹 🩺 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🪠 🧺 🧻 🚽 🚰
        🚿 🛁 🛀 🧼 🪥 🪒 🧽 🪣 🧴 🛎️ 🔑 🗝️ 🚪 🪑 🛋️ 🛏️ 🛌 🧸 🪆 🖼️ 🪞 🪟 🛍️ 🛒 🎁 🎈 🎀
        🪄 🎊 🎉 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷️ 🪧 📪 📫 📬 📭 📮 📯 📜 📃 📄 📑
        🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚
        📖 🔖 🧷 🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓
    """)),
)
