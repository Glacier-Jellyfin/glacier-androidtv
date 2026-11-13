package io.github.glacier_jellyfin.androidtv.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.glacier_jellyfin.androidtv.core.player.Iec61937Bursts
import io.github.glacier_jellyfin.androidtv.core.player.Iec61937Packer
import java.nio.ByteBuffer

/**
 * TrueHD and DTS-HD from the file, packed into IEC 61937 bursts for HDMI
 * (Iec61937.kt). The packer runs inside the audio sink, so anything it throws
 * stops playback.
 *
 * The last bytes choose the packer and how many buffers the rest arrives in,
 * the way Media3 hands over access units.
 */
object Iec61937Fuzzer {

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val trueHd = data.consumeBoolean()
        val packer = if (trueHd) Iec61937Packer.TrueHd() else Iec61937Packer.DtsHd()
        val burstSize = if (trueHd) TRUEHD_BURST_SIZE else 0
        val buffers = data.consumeInt(1, MAX_BUFFERS)
        val output = Iec61937Bursts()
        for (i in 1..buffers) {
            when (if (i > 1) data.consumeInt(0, EVENTS) else 0) {
                // A seek.
                1 -> packer.reset()

                // The end of the stream.
                2 -> (packer as? Iec61937Packer.TrueHd)?.finish(output)
            }
            val size = if (i == buffers) data.remainingBytes() else data.consumeInt(0, MAX_BUFFER)
            val bytes = data.consumeBytes(size)
            val input = ByteBuffer.wrap(bytes)
            packer.pack(input, output)
            check(!input.hasRemaining()) { "the packer left input" }
            checkBursts(output, burstSize)
            output.clear()
        }
        if (packer is Iec61937Packer.TrueHd) packer.finish(output)
        checkBursts(output, burstSize)
    }

    /** Whole bursts, each starting with the IEC 61937 sync words; TrueHD's all of the same size. */
    private fun checkBursts(output: Iec61937Bursts, burstSize: Int) {
        val bytes = output.buffer()
        if (bytes.remaining() == 0) return
        val size = bytes.remaining()
        check(SYNC.indices.all { bytes.get(it) == SYNC[it] }) { "a burst without sync words" }
        check(size % WORD_PAIR == 0) { "$size bytes are not whole IEC 60958 frames" }
        check(burstSize == 0 || size % burstSize == 0) { "$size bytes are not whole bursts" }
    }

    private const val MAX_BUFFERS = 8
    private const val MAX_BUFFER = 65536
    private const val EVENTS = 4
    private const val TRUEHD_BURST_SIZE = 61440
    private const val WORD_PAIR = 4
    private val SYNC = byteArrayOf(0x72, 0xF8.toByte(), 0x1F, 0x4E)
}
