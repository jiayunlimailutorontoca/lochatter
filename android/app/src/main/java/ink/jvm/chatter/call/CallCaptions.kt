package ink.jvm.chatter.call

/** One caption frame, already checked to belong to a call the UI still has open. */
data class CaptionFrame(
    val callId: String,
    val who: String,
    val text: String,
    val state: String,
    val phase: String?,
    val ts: Long,
)

/** A committed line, or the live partial still being spoken. */
data class CaptionLine(val who: String, val text: String, val live: Boolean)

/**
 * Subtitles for one assistant call. Frames for another call id, frames that arrive after [close],
 * and frames older than the last final line from that speaker are ignored.
 */
data class CaptionBoard(
    val callId: String,
    val lines: List<CaptionLine> = emptyList(),
    val draftUser: String = "",
    val draftAssistant: String = "",
    val phase: String = "idle",
    val phaseText: String = "",
    val closed: Boolean = false,
    val lastFinalTs: Map<String, Long> = emptyMap(),
    val lastFinalText: Map<String, String> = emptyMap(),
    val phaseTs: Long = Long.MIN_VALUE,
)

object CallCaptions {
    const val MAX_LINES = 40

    fun begin(callId: String) = CaptionBoard(callId)

    fun close(board: CaptionBoard): CaptionBoard = board.copy(closed = true)

    fun rows(board: CaptionBoard): List<CaptionLine> {
        val out = ArrayList<CaptionLine>(board.lines.size + 2)
        out.addAll(board.lines)
        if (board.draftUser.isNotBlank()) out.add(CaptionLine("user", board.draftUser, live = true))
        if (board.draftAssistant.isNotBlank()) out.add(CaptionLine("assistant", board.draftAssistant, live = true))
        return out
    }

    fun apply(board: CaptionBoard, frame: CaptionFrame): CaptionBoard {
        if (board.closed || frame.callId != board.callId) return board
        if (frame.who != "user" && frame.who != "assistant") return board
        if (frame.state != "partial" && frame.state != "final") return board
        val lastTs = board.lastFinalTs[frame.who] ?: Long.MIN_VALUE
        if (frame.state == "partial") {
            if (frame.ts <= lastTs) return board
            val next = if (frame.who == "user") board.copy(draftUser = frame.text) else board.copy(draftAssistant = frame.text)
            return stampPhase(next, frame)
        }
        if (frame.ts < lastTs) return board
        if (frame.ts == lastTs && frame.text == board.lastFinalText[frame.who].orEmpty()) return stampPhase(board, frame)
        val lines = if (frame.text.isBlank()) board.lines else (board.lines + CaptionLine(frame.who, frame.text, live = false)).takeLast(MAX_LINES)
        val committed = board.copy(
            lines = lines,
            draftUser = if (frame.who == "user") "" else board.draftUser,
            draftAssistant = if (frame.who == "assistant") "" else board.draftAssistant,
            lastFinalTs = board.lastFinalTs + (frame.who to frame.ts),
            lastFinalText = board.lastFinalText + (frame.who to frame.text),
        )
        return stampPhase(committed, frame)
    }

    private fun stampPhase(board: CaptionBoard, frame: CaptionFrame): CaptionBoard {
        val phase = frame.phase ?: return board
        if (frame.ts < board.phaseTs) return board
        return board.copy(phase = phase, phaseText = frame.text, phaseTs = frame.ts)
    }
}
