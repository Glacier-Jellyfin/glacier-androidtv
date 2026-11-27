package io.github.glacier_jellyfin.androidtv.core.data.geodata

/**
 * One downloadable place pack: the file of [level] (minimum inhabitants) as an asset of the geodata release.
 * [level] 0 is the finest pack, "All places", without a population filter.
 * [sha256] is the lower-case hex digest of the `.gz` file, [size] its length in bytes, [rawSize] the length of the
 * unpacked `.bin` (needed to check the free storage before the download).
 */
data class GeoPack(
    val level: Int,
    val file: String,
    val sha256: String,
    val size: Long,
    val rawSize: Long,
)

/**
 * The pinned table of place packs. Replace [ALL] and [BASE_URL] when a new geodata release is published; nothing else
 * has to change. The packs are built by `scripts/geodata`.
 */
object GeoPacks {

    /** Directory of the geodata release; every [GeoPack.file] is an asset in it. */
    const val BASE_URL = "https://github.com/Glacier-Jellyfin/glacier-androidtv/releases/download/geodata-2026-11/"

    val ALL: List<GeoPack> = listOf(
        GeoPack(
            level = 15_000,
            file = "places-15000.bin.gz",
            sha256 = "a5b54d64850b9c2b62e22f39be2b6dec890623038bc68d20c854f1910f988f0e",
            size = 844_362L,
            rawSize = 1_814_971L,
        ),
        GeoPack(
            level = 5_000,
            file = "places-5000.bin.gz",
            sha256 = "0e25f278d8fba16dac51973bfab84e972306b2102c293835db4ab5bd4706a1d9",
            size = 1_245_488L,
            rawSize = 2_579_155L,
        ),
        GeoPack(
            level = 1_000,
            file = "places-1000.bin.gz",
            sha256 = "849ae17fef0bc02d17735297527678a82e906d6345ac3e488cf74f4fd9f0454d",
            size = 2_744_169L,
            rawSize = 5_500_930L,
        ),
        GeoPack(
            level = 500,
            file = "places-500.bin.gz",
            sha256 = "09b5e82c249804f3671fb98d9d92ab0975b859f5fabb7818f06638baaeee3d2e",
            size = 3_329_820L,
            rawSize = 6_663_463L,
        ),
        GeoPack(
            level = 0,
            file = "places-all.bin.gz",
            sha256 = "c36e4ddfb2f4abba9014bb636fd1340bd91366e91001649edd40e153c9b5f246",
            size = 56_903_301L,
            rawSize = 119_845_846L,
        ),
    )

    /** The pack that is an asset of the APK (see [AssetPackSource]): only unpacked on the device, never downloaded. */
    const val BUNDLED_LEVEL = 15_000

    /** The levels the setting offers, coarsest first. */
    val LEVELS: List<Int> get() = ALL.map { it.level }

    fun forLevel(level: Int): GeoPack? = ALL.firstOrNull { it.level == level }

    /**
     * Maps a stored level to one that has a pack. `null` (off) stays `null` and a level with a pack stays as it is.
     * A level without a pack, for example 300 from an older version, becomes the finest pack that is coarser than it:
     * the smallest non-zero level that is at least the stored one. If there is none, it is [BUNDLED_LEVEL].
     */
    fun normalize(level: Int?): Int? = when {
        level == null -> null
        forLevel(level) != null -> level
        else -> LEVELS.filter { it != 0 && it >= level }.minOrNull() ?: BUNDLED_LEVEL
    }
}
