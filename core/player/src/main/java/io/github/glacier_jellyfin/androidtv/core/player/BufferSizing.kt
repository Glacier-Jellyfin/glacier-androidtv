package io.github.glacier_jellyfin.androidtv.core.player

/** How much the player may hold in memory, derived from the heap the app was given. */
internal object BufferSizing {

    private const val MB = 1024L * 1024L
    private const val HEAP_SHARE_PERCENT = 40
    private const val MAX_BYTES = 400L * MB

    /**
     * The byte target of the buffer: [HEAP_SHARE_PERCENT] % of the heap, but not below [floorBytes] (Media3's own
     * default, which a small heap must not push under) and not above 400 MB. High-bitrate direct play fills the
     * byte limit long before the time limit, so a fixed default keeps its buffer short.
     */
    fun targetBytes(maxMemoryBytes: Long, floorBytes: Int): Int {
        val share = maxMemoryBytes / 100 * HEAP_SHARE_PERCENT
        return share.coerceIn(floorBytes.toLong(), maxOf(floorBytes.toLong(), MAX_BYTES)).toInt()
    }
}
