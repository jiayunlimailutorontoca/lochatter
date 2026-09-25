package ink.jvm.chatter.media

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Timed captions from this phone's microphone, kept on disk after hangup.
 * The summary field stays empty until the user asks for one.
 */
object CallNotes {
    data class Line(val at: Long, val text: String)

    data class Note(
        val id: String,
        val at: Long,
        val video: Boolean,
        val seconds: Long,
        val trimmed: Boolean,
        val lines: List<Line>,
        val summary: String,
    )

    private const val MAX_NOTES = 40
    private const val MODEL_CHARS = 2000

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "call-notes")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _items = MutableStateFlow<List<Note>>(emptyList())
    val items: StateFlow<List<Note>> = _items.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Set after a call that produced captions. The UI shows that note once, then clears this. */
    val pendingId = MutableStateFlow<String?>(null)

    private var loaded = false

    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { load(app) }
    }

    fun stash(ctx: Context, video: Boolean, seconds: Long, lines: List<Line>, trimmed: Boolean) {
        if (lines.isEmpty()) return
        val app = ctx.applicationContext
        val note = Note(
            id = UUID.randomUUID().toString(),
            at = System.currentTimeMillis(),
            video = video,
            seconds = seconds,
            trimmed = trimmed,
            lines = lines,
            summary = "",
        )
        scope.launch {
            load(app)
            val next = (listOf(note) + _items.value.filter { it.id != note.id }).take(MAX_NOTES)
            _items.value = next
            write(app, next)
            pendingId.value = note.id
        }
    }

    fun setSummary(ctx: Context, id: String, summary: String) {
        val app = ctx.applicationContext
        scope.launch {
            load(app)
            val next = _items.value.map { if (it.id == id) it.copy(summary = summary.trim()) else it }
            _items.value = next
            write(app, next)
        }
    }

    fun delete(ctx: Context, id: String) {
        val app = ctx.applicationContext
        scope.launch {
            load(app)
            val next = _items.value.filter { it.id != id }
            _items.value = next
            write(app, next)
            if (pendingId.value == id) pendingId.value = null
        }
    }

    /** Text handed to the on-device model. The screen shows [Note.lines] in full. */
    fun modelText(note: Note): String {
        val body = note.lines.joinToString("\n") { "${clock(it.at)} ${it.text}" }
        val tail = if (body.length <= MODEL_CHARS) body else body.takeLast(MODEL_CHARS)
        return if (note.trimmed || body.length > MODEL_CHARS) "（前面的话已略）\n$tail" else tail
    }

    private fun load(ctx: Context) {
        if (loaded) return
        _items.value = read(File(ctx.filesDir, "call-notes.json"))
        loaded = true
        _ready.value = true
    }

    private fun write(ctx: Context, notes: List<Note>) {
        val dest = File(ctx.filesDir, "call-notes.json")
        val part = File(ctx.filesDir, "call-notes.json.part")
        val arr = JSONArray()
        for (note in notes) {
            val lines = JSONArray()
            for (line in note.lines) {
                lines.put(JSONObject().put("at", line.at).put("text", line.text))
            }
            arr.put(
                JSONObject()
                    .put("id", note.id)
                    .put("at", note.at)
                    .put("video", note.video)
                    .put("seconds", note.seconds)
                    .put("trimmed", note.trimmed)
                    .put("summary", note.summary)
                    .put("lines", lines),
            )
        }
        part.writeText(arr.toString(), Charsets.UTF_8)
        if (dest.exists() && !dest.delete()) return
        if (!part.renameTo(dest)) part.delete()
    }

    private fun read(file: File): List<Note> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val out = ArrayList<Note>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val linesJson = o.optJSONArray("lines") ?: JSONArray()
                val lines = ArrayList<Line>(linesJson.length())
                for (j in 0 until linesJson.length()) {
                    val line = linesJson.optJSONObject(j) ?: continue
                    val text = line.optString("text").trim()
                    if (text.isEmpty()) continue
                    lines.add(Line(line.optLong("at"), text))
                }
                if (lines.isEmpty()) continue
                out.add(
                    Note(
                        id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                        at = o.optLong("at"),
                        video = o.optBoolean("video"),
                        seconds = o.optLong("seconds"),
                        trimmed = o.optBoolean("trimmed"),
                        lines = lines,
                        summary = o.optString("summary"),
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun clock(at: Long): String {
        val z = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        return "%02d:%02d:%02d".format(z.hour, z.minute, z.second)
    }
}
