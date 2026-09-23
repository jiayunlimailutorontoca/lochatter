package ink.jvm.chatter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * Renders a practical subset of Markdown with plain Compose: headers, bold/italic/strike, inline and fenced code,
 * lists, quotes, rules, tables, links (plus bare URLs and phone numbers like [LinkedText]). Unknown syntax is shown
 * verbatim; the scanner is line-by-line and linear, so long inputs stay cheap.
 */
@Composable
fun MarkdownText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier) {
        blocks.forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.height(if (b is Block.Item && blocks[i - 1] is Block.Item) 2.dp else 6.dp))
            when (b) {
                is Block.Heading -> Inline(b.text, scaled(style, when (b.level) { 1 -> 1.5f; 2 -> 1.3f; 3 -> 1.15f; else -> 1f }).copy(fontWeight = FontWeight.Bold), color)
                is Block.Para -> Inline(b.text, style, color)
                is Block.Code -> CodeBlock(b, style, color)
                is Block.Item -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
                    Text(b.marker, style = style, color = color, modifier = Modifier.padding(end = 6.dp).widthIn(min = 12.dp))
                    Inline(b.text, style, color, Modifier.weight(1f))
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.4f)))
                    Inline(b.text, style, color.copy(alpha = 0.85f), Modifier.padding(start = 8.dp))
                }
                Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 2.dp), color = color.copy(alpha = 0.25f))
                is Block.Table -> TableBlock(b, scaled(style, 0.9f), color)
            }
        }
    }
}

/** Plain text for notifications, previews and speech: markup removed, link text kept, list markers become "- ". */
fun stripMarkdown(text: String): String = buildString {
    for (b in parseBlocks(text)) {
        if (b === Block.Rule) continue
        if (isNotEmpty()) append('\n')
        when (b) {
            is Block.Heading -> append(plain(b.text))
            is Block.Para -> append(plain(b.text))
            is Block.Code -> append(b.text)
            is Block.Item -> { repeat(b.depth) { append("  ") }; append("- ").append(plain(b.text)) }
            is Block.Quote -> append(plain(b.text))
            is Block.Table -> b.rows.forEachIndexed { i, r -> if (i > 0) append('\n'); append(r.joinToString(" ") { plain(it) }) }
            Block.Rule -> {}
        }
    }
}.trim()

/** First non-empty line of [stripMarkdown]. */
fun markdownFirstLine(text: String): String = stripMarkdown(text).lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""

// ---- blocks ----

private sealed class Block {
    class Heading(val level: Int, val text: String) : Block()
    class Para(val text: String) : Block()
    class Code(val lang: String, val text: String) : Block()
    class Item(val depth: Int, val marker: String, var text: String) : Block()
    class Quote(val text: String) : Block()
    data object Rule : Block()
    class Table(val rows: List<List<String>>) : Block()
}

private fun parseBlocks(src: String): List<Block> {
    val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotEmpty()) { out += Block.Para(para.toString()); para.clear() } }
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val t = line.trimStart()
        if (t.startsWith("```") || t.startsWith("~~~")) {
            flush()
            val fence = t.take(3)
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) { if (buf.isNotEmpty()) buf.append('\n'); buf.append(lines[i]); i++ }
            out += Block.Code(t.drop(3).trim(), buf.toString())
            i++
            continue
        }
        if (t.isEmpty()) { flush(); i++; continue }
        if (t[0] == '#') {
            var lvl = 0
            while (lvl < t.length && t[lvl] == '#') lvl++
            if (lvl <= 6 && lvl < t.length && t[lvl] == ' ') {
                flush()
                var h = t.substring(lvl).trim()
                val closing = h.trimEnd('#')
                if (closing.length < h.length && (closing.isEmpty() || closing.endsWith(' '))) h = closing.trim() // "## title ##", but keep "# C#"
                out += Block.Heading(lvl, h)
                i++
                continue
            }
        }
        if (isRule(t)) { flush(); out += Block.Rule; i++; continue }
        if (t[0] == '>') {
            flush()
            val buf = StringBuilder()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(lines[i].trimStart().substring(1).removePrefix(" ").trimEnd())
                i++
            }
            out += Block.Quote(buf.toString())
            continue
        }
        if ('|' in t && i + 1 < lines.size && isTableSep(lines[i + 1])) {
            flush()
            val rows = ArrayList<List<String>>()
            rows += tableRow(t)
            i += 2
            while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) { rows += tableRow(lines[i].trim()); i++ }
            out += Block.Table(rows)
            continue
        }
        val item = listItem(raw)
        if (item != null) { flush(); out += item; i++; continue }
        val last = out.lastOrNull()
        if (para.isEmpty() && last is Block.Item && raw.startsWith("  ")) { last.text += "\n" + t; i++; continue } // lazy continuation
        if (para.isNotEmpty()) para.append('\n')
        para.append(t)
        i++
    }
    flush()
    return out
}

private fun isRule(t: String): Boolean {
    if (t.length < 3) return false
    val c = t[0]
    if (c != '-' && c != '*' && c != '_') return false
    var n = 0
    for (ch in t) { if (ch == c) n++ else if (ch != ' ') return false }
    return n >= 3
}

private fun isTableSep(line: String): Boolean {
    val t = line.trim()
    return t.isNotEmpty() && '-' in t && ('|' in t || ':' in t) && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun tableRow(t: String): List<String> = t.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun listItem(raw: String): Block.Item? {
    var width = 0
    var p = 0
    while (p < raw.length && (raw[p] == ' ' || raw[p] == '\t')) { width += if (raw[p] == '\t') 2 else 1; p++ }
    val t = raw.substring(p)
    val depth = (width / 2).coerceAtMost(5)
    if (t.length >= 2 && (t[0] == '-' || t[0] == '*' || t[0] == '+') && t[1] == ' ') {
        return Block.Item(depth, if (depth % 2 == 0) "•" else "◦", t.substring(2).trim())
    }
    var d = 0
    while (d < t.length && d < 3 && t[d].isDigit()) d++
    if (d in 1..3 && d + 1 < t.length && (t[d] == '.' || t[d] == ')') && t[d + 1] == ' ') {
        return Block.Item(depth, t.substring(0, d) + ".", t.substring(d + 2).trim())
    }
    return null
}

// ---- inline ----

private class Span(val text: String, val bold: Boolean, val italic: Boolean, val strike: Boolean, val code: Boolean, val url: String?)

private const val ESCAPABLE = "\\`*_~[]()#>|-+.!{}"
private const val URL_STOP = "<>\"'，。、；：！？（）【】《》「」"

private fun plain(text: String): String = tokenize(text).joinToString("") { it.text }

private fun tokenize(text: String): List<Span> {
    val out = ArrayList<Span>()
    val buf = StringBuilder()
    var bold = false
    var italic = false
    var strike = false
    fun flush() { if (buf.isNotEmpty()) { out += Span(buf.toString(), bold, italic, strike, false, null); buf.clear() } }
    val nextAt = HashMap<String, Int>() // next occurrence per delimiter (-1 = none): unmatched markers stay linear
    val noCodeClose = HashSet<Int>() // backtick-run lengths with no closer anywhere later
    fun hasLater(d: String, from: Int): Boolean {
        val cached = nextAt[d]
        if (cached != null && (cached == -1 || cached >= from)) return cached != -1
        val idx = text.indexOf(d, from)
        nextAt[d] = idx
        return idx >= 0
    }
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        val prev = if (i > 0) text[i - 1] else ' '
        when {
            c == '\\' && i + 1 < n && text[i + 1] in ESCAPABLE -> { buf.append(text[i + 1]); i += 2 }
            c == '`' -> {
                var k = 1
                while (i + k < n && text[i + k] == '`') k++
                val ticks = text.substring(i, i + k)
                val close = if (k !in noCodeClose && hasLater(ticks, i + k)) codeClose(text, i + k, k) else -1
                if (close < 0) { noCodeClose += k; buf.append(ticks); i += k } else {
                    flush()
                    out += Span(text.substring(i + k, close), bold, italic, strike, true, null)
                    i = close + k
                }
            }
            c == '*' || c == '_' -> {
                var k = 1
                while (i + k < n && text[i + k] == c) k++
                val next = if (i + k < n) text[i + k] else ' '
                val canOpen = !next.isWhitespace() && (c == '*' || !prev.isLetterOrDigit()) // "_" never opens inside a word (snake_case)
                val canClose = !prev.isWhitespace() && (c == '*' || !next.isLetterOrDigit())
                var used = 0
                if (k >= 2) {
                    if (bold && canClose) { flush(); bold = false; used = 2 }
                    else if (!bold && canOpen && hasLater("$c$c", i + k)) { flush(); bold = true; used = 2 }
                }
                if (k - used >= 1) {
                    if (italic && canClose) { flush(); italic = false; used++ }
                    else if (!italic && canOpen && hasLater(c.toString(), i + k)) { flush(); italic = true; used++ }
                }
                if (used < k) buf.append(text, i + used, i + k)
                i += k
            }
            c == '~' && i + 1 < n && text[i + 1] == '~' -> {
                val next = if (i + 2 < n) text[i + 2] else ' '
                if (strike && !prev.isWhitespace()) { flush(); strike = false }
                else if (!strike && !next.isWhitespace() && hasLater("~~", i + 2)) { flush(); strike = true }
                else buf.append("~~")
                i += 2
            }
            c == '[' && hasLater("](", i + 1) -> {
                val link = parseLink(text, i)
                if (link == null) { buf.append(c); i++ } else {
                    flush()
                    out += Span(link.first, bold, italic, strike, false, link.second)
                    i = link.third
                }
            }
            c == 'h' && !prev.isLetterOrDigit() && (text.startsWith("http://", i) || text.startsWith("https://", i)) -> {
                val end = urlEnd(text, i)
                flush()
                val u = text.substring(i, end)
                out += Span(u, bold, italic, strike, false, u)
                i = end
            }
            c == '1' && !prev.isLetterOrDigit() && isPhone(text, i) -> {
                flush()
                val p = text.substring(i, i + 11)
                out += Span(p, bold, italic, strike, false, "tel:$p")
                i += 11
            }
            else -> { buf.append(c); i++ }
        }
    }
    flush()
    return out
}

/** Index of a run of exactly [k] backticks at or after [from], or -1. */
private fun codeClose(text: String, from: Int, k: Int): Int {
    val ticks = "`".repeat(k)
    var at = from
    while (true) {
        val idx = text.indexOf(ticks, at)
        if (idx < 0) return -1
        val after = idx + k
        if (idx > from && (after >= text.length || text[after] != '`')) return idx
        at = after
        while (at < text.length && text[at] == '`') at++
    }
}

/** `[label](url)` at [i] → (label, url, end) when the url has a safe scheme; nested brackets in the label are allowed. */
private fun parseLink(text: String, i: Int): Triple<String, String, Int>? {
    var depth = 0
    var j = i
    val labelLimit = minOf(text.length, i + 512) // bounded scans keep a wall of "[" linear
    while (j < labelLimit) {
        val ch = text[j]
        if (ch == '[') depth++ else if (ch == ']') { depth--; if (depth == 0) break } else if (ch == '\n') return null
        j++
    }
    if (j >= labelLimit || j + 1 >= text.length || text[j + 1] != '(') return null
    var e = j + 2
    val urlLimit = minOf(text.length, e + 2048)
    while (e < urlLimit && text[e] != ')') { if (text[e].isWhitespace()) return null; e++ }
    if (e >= urlLimit) return null
    val url = text.substring(j + 2, e)
    val ok = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:") || url.startsWith("tel:")
    if (!ok) return null
    val label = plain(text.substring(i + 1, j)).ifEmpty { url }
    return Triple(label, url, e + 1)
}

private fun urlEnd(text: String, start: Int): Int {
    var e = start
    while (e < text.length && !text[e].isWhitespace() && text[e] !in URL_STOP) e++
    // Trailing punctuation and unbalanced closing brackets belong to the sentence, not the URL.
    var paren = 0; var square = 0; var curly = 0
    for (p in start until e) when (text[p]) { '(' -> paren++; ')' -> paren--; '[' -> square++; ']' -> square--; '{' -> curly++; '}' -> curly-- }
    while (e > start) {
        val ch = text[e - 1]
        val drop = when (ch) {
            ')' -> paren < 0
            ']' -> square < 0
            '}' -> curly < 0
            else -> ch in ".,;:!?*_~`"
        }
        if (!drop) break
        when (ch) { ')' -> paren++; ']' -> square++; '}' -> curly++ }
        e--
    }
    return e
}

private fun isPhone(text: String, i: Int): Boolean {
    if (i + 11 > text.length || text[i + 1] !in '3'..'9') return false
    for (p in i until i + 11) if (!text[p].isDigit()) return false
    return i + 11 == text.length || !text[i + 11].isLetterOrDigit()
}

// ---- rendering helpers ----

@Composable
private fun Inline(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val annotated = remember(text, color) {
        buildAnnotatedString {
            for (s in tokenize(text)) {
                val st = SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (s.code) FontFamily.Monospace else null,
                    background = if (s.code) color.copy(alpha = 0.12f) else Color.Unspecified,
                )
                if (s.url == null) withStyle(st) { append(s.text) } else {
                    val linkStyle = st.copy(color = color, textDecoration = TextDecoration.Underline, fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Medium)
                    withLink(LinkAnnotation.Url(s.url, TextLinkStyles(style = linkStyle)) { link -> openLink(ctx, (link as LinkAnnotation.Url).url) }) { append(s.text) }
                }
            }
        }
    }
    Text(annotated, style = style, color = color, modifier = modifier)
}

private fun openLink(ctx: Context, u: String) {
    runCatching { ctx.startActivity(Intent(if (u.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(u))) }
        .onFailure { Toast.makeText(ctx, "无法打开", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun CodeBlock(b: Block.Code, style: TextStyle, color: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.08f))) {
        if (b.lang.isNotEmpty()) Text(b.lang, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.6f), modifier = Modifier.padding(start = 10.dp, top = 6.dp))
        Text(
            b.text, style = scaled(style, 0.9f).copy(fontFamily = FontFamily.Monospace), color = color, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
        )
    }
}

@Composable
private fun TableBlock(b: Block.Table, style: TextStyle, color: Color) {
    val cols = b.rows.maxOf { it.size }
    val line = color.copy(alpha = 0.2f)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, line, RoundedCornerShape(8.dp))) {
        b.rows.forEachIndexed { r, row ->
            if (r > 0) HorizontalDivider(color = line)
            Row(if (r == 0) Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f)) else Modifier.fillMaxWidth()) {
                for (c in 0 until cols) {
                    Inline(row.getOrElse(c) { "" }, if (r == 0) style.copy(fontWeight = FontWeight.Bold) else style, color, Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp))
                }
            }
        }
    }
}

private fun scaled(style: TextStyle, k: Float): TextStyle = style.copy(
    fontSize = if (style.fontSize.isSpecified) style.fontSize * k else TextUnit.Unspecified,
    lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * k else TextUnit.Unspecified,
)
