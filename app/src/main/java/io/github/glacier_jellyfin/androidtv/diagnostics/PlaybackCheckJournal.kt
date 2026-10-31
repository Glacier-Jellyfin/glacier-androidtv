package io.github.glacier_jellyfin.androidtv.diagnostics

import android.content.Context
import io.github.glacier_jellyfin.androidtv.core.player.CheckResult
import io.github.glacier_jellyfin.androidtv.core.player.PlaybackCheck
import java.io.File

/**
 * The playback check's progress in a file, so the app sees what happened when
 * a decoder took the check's own process down: that sample is started but
 * has no result, and the run never ended.
 */
class PlaybackCheckJournal(context: Context) {

    private val file = File(context.filesDir, "playback-check.txt")

    class Entries(val results: List<CheckResult>, val open: Int?, val ended: Boolean)

    fun begin() = write { file.writeText("") }

    fun starting(index: Int) = write { file.appendText("start\t$index\n") }

    fun finished(index: Int, result: CheckResult) = write {
        val fields = listOf(
            index.toString(),
            result.outcome.name,
            result.beyondDeclared.toString(),
            result.decoder.orEmpty(),
            result.audioOutput.orEmpty(),
            result.error.orEmpty(),
            result.droppedFrames.toString(),
        ).map { it.replace('\t', ' ').replace('\n', ' ') }
        file.appendText("result\t" + fields.joinToString("\t") + "\n")
    }

    fun end() = write { file.appendText("end\n") }

    fun read(): Entries {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList()).map { it.split('\t') }
        val results = lines.filter { it.first() == "result" && it.size >= 8 }.mapNotNull { fields ->
            val sample = PlaybackCheck.SAMPLES.getOrNull(fields[1].toIntOrNull() ?: -1) ?: return@mapNotNull null
            CheckResult(
                sample = sample,
                outcome = runCatching { CheckResult.Outcome.valueOf(fields[2]) }.getOrDefault(CheckResult.Outcome.Fails),
                beyondDeclared = fields[3].toBoolean(),
                decoder = fields[4].ifEmpty { null },
                audioOutput = fields[5].ifEmpty { null },
                error = fields[6].ifEmpty { null },
                droppedFrames = fields[7].toIntOrNull() ?: 0,
            )
        }
        val started = lines.filter { it.first() == "start" }.mapNotNull { it.getOrNull(1)?.toIntOrNull() }
        val finished = lines.filter { it.first() == "result" }.mapNotNull { it.getOrNull(1)?.toIntOrNull() }.toSet()
        return Entries(results, open = started.lastOrNull()?.takeIf { it !in finished }, ended = lines.any { it.first() == "end" })
    }

    /**
     * The sample a run stopped at without a result or an end: its process died while it played.
     * Only just now: a run cut short long ago (the TV switched off) is not taken up again days later.
     */
    fun interruptedAt(): Int? {
        if (System.currentTimeMillis() - file.lastModified() > RESUME_WITHIN_MS) return null
        return read().takeIf { !it.ended }?.open
    }

    private companion object {
        const val RESUME_WITHIN_MS = 10 * 60_000L
    }

    // The journal only helps the check; failing to write it must not stop the check.
    private fun write(block: () -> Unit) {
        runCatching(block)
    }
}
