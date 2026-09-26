package ink.jvm.chatter.media

import android.content.Context
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * One digest per local day, using whichever summary channel the user has selected.
 * Records stay on this phone. A missed day is not sent to the model later.
 */
object DailySummary {
    data class Note(
        val date: String,
        val talk: String,
        val mood: String,
        val memories: List<String>,
        val trimmed: Boolean,
        val usedModel: Boolean,
        val channel: String,
    )

    private val gate = Mutex()
    private var loaded = false
    private var running = false
    private var retryAfter = 0L
    private val considered = HashSet<String>()
    private val memory = ArrayList<String>()
    private val rows = ArrayList<Note>()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _running = MutableStateFlow(false)
    val runningNow: StateFlow<Boolean> = _running.asStateFlow()

    private val moods = setOf("平稳", "开心", "担心", "生气", "说不准")

    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        gate.withLock { ensureLoaded(ctx) }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs(ctx).dailySummary = on
    }

    suspend fun delete(ctx: Context, date: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            ensureLoaded(ctx)
            val gone = rows.filter { it.date == date }
            rows.removeAll { it.date == date }
            gone.flatMap { it.memories }.forEach { memory.remove(it) }
            persist(ctx)
        }
    }

    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        gate.withLock {
            ensureLoaded(ctx)
            rows.clear()
            memory.clear()
            persist(ctx)
        }
    }

    /** Foreground, not in a call. Does nothing when the switch is off or the channel is not ready. */
    suspend fun maybeRun(ctx: Context, repo: ChatRepository, inCall: Boolean) {
        if (!Prefs(ctx).dailySummary || inCall) return
        if (LocalSummary.phase.value != null) return
        if (System.currentTimeMillis() < retryAfter) return
        val job = gate.withLock {
            ensureLoaded(ctx)
            if (running) return
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            closeEmpty(ctx, repo, today.minusDays(1), zone)
            val existing = rows.find { it.date == today.toString() }
            if (existing?.usedModel == true) return
            if (!LocalSummary.ready(ctx)) return
            val packed = pack(ctx, repo, today, zone) ?: return
            running = true
            _running.value = true
            packed
        }
        try {
            val raw = LocalSummary.daily(ctx, job.source, job.memory)
            gate.withLock {
                val parsed = parse(raw, job.source, job.trimmed, job.channel)
                rows.removeAll { it.date == job.date }
                rows.add(parsed.copy(date = job.date))
                parsed.memories.forEach { line ->
                    if (line !in memory) memory.add(0, line)
                }
                while (memory.size > 24) memory.removeAt(memory.lastIndex)
                persist(ctx)
                running = false
                _running.value = false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            gate.withLock {
                running = false
                _running.value = false
            }
            throw e
        } catch (_: Exception) {
            gate.withLock {
                running = false
                _running.value = false
                retryAfter = System.currentTimeMillis() + 30 * 60 * 1000L
            }
        }
    }

    private suspend fun closeEmpty(ctx: Context, repo: ChatRepository, day: LocalDate, zone: ZoneId) {
        val key = day.toString()
        if (key in considered || rows.any { it.date == key }) {
            considered.add(key)
            return
        }
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val texts = peerLines(repo.messagesOnDay(start, end), repo)
        considered.add(key)
        if (texts.isNotEmpty()) return
        rows.add(
            Note(
                date = day.toString(),
                talk = "这天没有文字消息",
                mood = "说不准",
                memories = emptyList(),
                trimmed = false,
                usedModel = false,
                channel = "",
            ),
        )
        persist(ctx)
    }

    private data class Packed(val date: String, val source: String, val memory: List<String>, val trimmed: Boolean, val channel: String)

    private suspend fun pack(ctx: Context, repo: ChatRepository, day: LocalDate, zone: ZoneId): Packed? {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val lines = peerLines(repo.messagesOnDay(start, end), repo)
        if (lines.isEmpty()) return null
        val (source, trimmed) = tail(lines.joinToString("\n"), 6000)
        return Packed(day.toString(), source, memory.toList(), trimmed, LocalSummary.current(ctx).title)
    }

    private fun peerLines(messages: List<LocalMessage>, repo: ChatRepository): List<String> {
        val me = repo.me
        val mine = repo.prefs.userName.ifBlank { "我" }
        val peer = repo.prefs.peerName.ifBlank { "对方" }
        return messages.mapNotNull { m ->
            if (m.fromBot || m.toBot || m.kind != "text") return@mapNotNull null
            val body = m.text?.trim().orEmpty()
            if (body.isEmpty()) return@mapNotNull null
            val who = if (m.from == me) mine else peer
            "$who：$body"
        }
    }

    private fun tail(text: String, max: Int): Pair<String, Boolean> {
        if (text.length <= max) return text to false
        val cut = text.takeLast(max)
        val nl = cut.indexOf('\n')
        val body = if (nl in 1 until 120) cut.substring(nl + 1) else cut
        return body to true
    }

    internal fun parse(raw: String, source: String, trimmed: Boolean, channel: String): Note {
        val talkRaw = Regex("聊了什么[:：]\\s*(.+)").find(raw)?.groupValues?.get(1)
            ?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val moodRaw = Regex("心情[:：]\\s*(\\S+)").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val memRaw = Regex("可记住的事[:：]\\s*(.*)").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val talkBody = talkRaw.take(150).ifBlank { "这次没有整理出内容" }
        val talk = if (trimmed && !talkBody.startsWith("前面已略")) "前面已略。$talkBody" else talkBody
        val mood = if (moodRaw in moods) moodRaw else "说不准"
        val flat = source.filter { !it.isWhitespace() }
        val memories = memRaw.split('|', '｜', '\n')
            .map { it.trim().trim('。', '；', ';') }
            .filter { it.isNotEmpty() && it != "无" && it != "没有" }
            .map { it.take(40) }
            .filter { line ->
                val key = line.filter { !it.isWhitespace() }
                key.length >= 2 && flat.contains(key)
            }
            .distinct()
            .take(3)
        return Note("", talk, mood, memories, trimmed, usedModel = true, channel = channel)
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val file = file(ctx)
        if (!file.isFile) {
            _notes.value = emptyList()
            return
        }
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        memory.clear()
        val mem = root.optJSONArray("memory") ?: JSONArray()
        for (i in 0 until mem.length()) mem.optString(i).takeIf { it.isNotBlank() }?.let { memory.add(it) }
        rows.clear()
        val arr = root.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mems = ArrayList<String>()
            val inner = o.optJSONArray("memories") ?: JSONArray()
            for (j in 0 until inner.length()) inner.optString(j).takeIf { it.isNotBlank() }?.let { mems.add(it) }
            val date = o.optString("date")
            if (date.isBlank()) continue
            rows.add(
                Note(
                    date = date,
                    talk = o.optString("talk"),
                    mood = o.optString("mood").ifBlank { "说不准" },
                    memories = mems,
                    trimmed = o.optBoolean("trimmed"),
                    usedModel = o.optBoolean("usedModel"),
                    channel = o.optString("channel"),
                ),
            )
        }
        _notes.value = rows.sortedByDescending { it.date }
    }

    private fun persist(ctx: Context) {
        val cutoff = LocalDate.now().minusDays(90).toString()
        rows.removeAll { it.date < cutoff }
        val notes = JSONArray()
        rows.sortedBy { it.date }.forEach { n ->
            notes.put(
                JSONObject()
                    .put("date", n.date)
                    .put("talk", n.talk)
                    .put("mood", n.mood)
                    .put("memories", JSONArray(n.memories))
                    .put("trimmed", n.trimmed)
                    .put("usedModel", n.usedModel)
                    .put("channel", n.channel),
            )
        }
        val root = JSONObject().put("memory", JSONArray(memory)).put("notes", notes)
        file(ctx).writeText(root.toString())
        _notes.value = rows.sortedByDescending { it.date }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "daily-summary.json")
}
