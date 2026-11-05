package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class Iec61937OutputTest {

    /** A track that takes at most [chunk] bytes per write, like a full AudioTrack. */
    private class SlowTrack(var chunk: Int) {
        var written = 0
        val output: AudioOutput = Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
            when (method.name) {
                "write" -> {
                    val buffer = args[0] as ByteBuffer
                    val count = minOf(chunk, buffer.remaining())
                    buffer.position(buffer.position() + count)
                    written += count
                    !buffer.hasRemaining()
                }
                "getSampleRate" -> 192_000
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioOutput
    }

    @Test
    fun `reports a buffer as written exactly when it took it`() {
        val track = SlowTrack(chunk = 1000)
        val output = Iec61937Output(track.output, Iec61937Packer.DtsHd(), 48_000)
        val frames = dtsFrames()

        // The sink's rule: a buffer is done once write returns true, and only then; an
        // emptied buffer must never be reported done later (Media3 checks this).
        val first = ByteBuffer.wrap(frames[0])
        assertTrue(output.write(first, 1, 0))
        assertFalse(first.hasRemaining())

        val second = ByteBuffer.wrap(frames[1])
        while (!output.write(second, 1, 0)) {
            assertEquals("Input waits untouched while bursts are pending", frames[1].size, second.remaining())
        }
        assertFalse(second.hasRemaining())
    }

    @Test
    fun `writes every burst once the track has room`() {
        val track = SlowTrack(chunk = 1000)
        val output = Iec61937Output(track.output, Iec61937Packer.DtsHd(), 48_000)
        val expected = Iec61937Bursts()
        val packer = Iec61937Packer.DtsHd()
        dtsFrames().take(4).forEach { frame ->
            packer.pack(ByteBuffer.wrap(frame), expected)
            val buffer = ByteBuffer.wrap(frame)
            while (!output.write(buffer, 1, 0)) Unit
        }
        track.chunk = Int.MAX_VALUE
        output.write(ByteBuffer.allocate(0), 0, 0)
        assertEquals(expected.size, track.written)
    }

    private fun dtsFrames(): List<ByteArray> {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("iec61937/dts.dts")).use { it.readBytes() }
        val frames = mutableListOf<ByteArray>()
        var position = 0
        while (position + 9 <= stream.size) {
            val size = ((((stream[position + 5].toInt() and 0xff) shl 16) or ((stream[position + 6].toInt() and 0xff) shl 8) or
                (stream[position + 7].toInt() and 0xff)) shr 4 and 0x3fff) + 1
            frames += stream.copyOfRange(position, minOf(position + size, stream.size))
            position += size
        }
        return frames
    }
}
