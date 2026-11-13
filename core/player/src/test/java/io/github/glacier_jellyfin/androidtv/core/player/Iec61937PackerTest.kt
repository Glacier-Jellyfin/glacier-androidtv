package io.github.glacier_jellyfin.androidtv.core.player

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bursts must match ffmpeg's spdif muxer byte for byte (scripts/media/make-playback-samples.py). */
class Iec61937PackerTest {

    @Test
    fun `packs TrueHD into MAT frames like ffmpeg`() {
        val stream = resource("truehd.thd")
        val bursts = Iec61937Bursts()
        val packer = Iec61937Packer.TrueHd()
        // Media3 hands 16 access units per buffer; the packer splits them again.
        var position = 0
        while (position < stream.size) {
            var end = position
            repeat(16) {
                if (end + 2 <= stream.size) {
                    end += (((stream[end].toInt() and 0x0f) shl 8) or (stream[end + 1].toInt() and 0xff)) * 2
                }
            }
            packer.pack(ByteBuffer.wrap(stream, position, minOf(end, stream.size) - position), bursts)
            position = end
        }
        assertEquals(expected("truehd.thd"), sha256(bursts))
    }

    @Test
    fun `packs DTS as type IV bursts like ffmpeg`() {
        val stream = resource("dts.dts")
        val bursts = Iec61937Bursts()
        val packer = Iec61937Packer.DtsHd()
        var position = 0
        while (position + 9 <= stream.size) {
            // The core's frame size: the whole frame of a stream without extensions.
            val frameHeader = ((stream[position + 5].toInt() and 0xff) shl 16) or
                ((stream[position + 6].toInt() and 0xff) shl 8) or
                (stream[position + 7].toInt() and 0xff)
            val size = (frameHeader shr 4 and 0x3fff) + 1
            packer.pack(ByteBuffer.wrap(stream, position, size), bursts)
            position += size
        }
        assertEquals(expected("dts.dts"), sha256(bursts))
    }

    @Test
    fun `keeps a partial MAT frame for the next buffer`() {
        val stream = resource("truehd.thd")
        val first = ((stream[0].toInt() and 0x0f) shl 8 or (stream[1].toInt() and 0xff)) * 2
        val bursts = Iec61937Bursts()
        Iec61937Packer.TrueHd().pack(ByteBuffer.wrap(stream, 0, first), bursts)
        assertEquals(0, bursts.size)
    }

    @Test
    fun `completes the last MAT frame at the end of the stream`() {
        val stream = resource("truehd.thd")
        val first = ((stream[0].toInt() and 0x0f) shl 8 or (stream[1].toInt() and 0xff)) * 2
        val bursts = Iec61937Bursts()
        val packer = Iec61937Packer.TrueHd()
        packer.pack(ByteBuffer.wrap(stream, 0, first), bursts)
        packer.finish(bursts)
        assertEquals(61440, bursts.size)
        // The MAT end code, swapped into little-endian words, closes the payload.
        val end = bursts.buffer().array().copyOfRange(8 + 61424 - 4, 8 + 61424).map { it.toInt() and 0xff }
        assertEquals(listOf(0x00, 0x00, 0x11, 0x97), end)
    }

    @Test
    fun `drops a DTS frame whose core does not fit the burst`() {
        // The fuzzer's find: too large for the 2048-byte burst, and a core of 2049 bytes for the fallback.
        val bursts = Iec61937Bursts()
        Iec61937Packer.DtsHd().pack(ByteBuffer.wrap(dtsFrame(coreSize = 2049, size = 2788)), bursts)
        assertEquals(0, bursts.size)
    }

    @Test
    fun `drops a DTS frame whose core is larger than the frame`() {
        val bursts = Iec61937Bursts()
        val packer = Iec61937Packer.DtsHd()
        // Too large for the burst: only cores go from here on.
        packer.pack(ByteBuffer.wrap(dtsFrame(coreSize = 1000, size = 2788)), bursts)
        assertEquals(2048, bursts.size)
        packer.pack(ByteBuffer.wrap(dtsFrame(coreSize = 1000, size = 100)), bursts)
        assertEquals(2048, bursts.size)
    }

    /** A DTS frame of [size] bytes: a core header of one block at 48 kHz (bursts of 2048 bytes) giving [coreSize]. */
    private fun dtsFrame(coreSize: Int, size: Int): ByteArray {
        val field = (coreSize - 1) shl 4
        val header = intArrayOf(0x7f, 0xfe, 0x80, 0x01, 0x08, field shr 16, field shr 8, field, 0xf4)
        return ByteArray(size).also { frame -> header.forEachIndexed { i, byte -> frame[i] = byte.toByte() } }
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("iec61937/$name")).use { it.readBytes() }

    private fun expected(name: String): String =
        String(resource("expected.txt")).lines().first { it.startsWith("$name ") }.substringAfter(' ')

    private fun sha256(bursts: Iec61937Bursts): String {
        val buffer = bursts.buffer()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
