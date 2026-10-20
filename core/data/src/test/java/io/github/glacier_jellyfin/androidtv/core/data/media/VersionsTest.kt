package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionsTest {

    private fun version(id: String, width: Int, height: Int, hdr: HdrFormat? = null, edition: String? = null, runtimeMs: Long? = null) =
        MediaVersion(
            id = id,
            name = edition,
            edition = edition,
            width = width,
            height = height,
            quality = VideoQuality(uhd = width >= 3200, hdr = hdr).takeIf { it.uhd || hdr != null },
            videoCodec = "hevc",
            sizeBytes = null,
            runtimeMs = runtimeMs,
            tracks = null,
            trickplay = null,
        )

    private val uhd = version("uhd", 3840, 2160, HdrFormat.Hdr10)
    private val fullHd = version("fhd", 1920, 1080)
    private val hd = version("hd", 1280, 720)
    private val all = listOf(uhd, fullHd, hd)

    // A film as a theatrical cut in two sizes, a longer Director's Cut and a Noir cut.
    private val cut = version("dc", 1920, 1080, edition = "Director's Cut", runtimeMs = 9_000_000)
    private val noir = version("noir", 1920, 1080, edition = "Noir")
    private val cuts = listOf(uhd, cut, fullHd, noir)

    @Test
    fun `sizes go by width first`() {
        assertEquals(Resolution.FullHd, Resolution.of(1920, 800))
        assertEquals(Resolution.Uhd, Resolution.of(3840, 1600))
        assertEquals(Resolution.Hd, Resolution.of(1280, 536))
        assertEquals(Resolution.Sd, Resolution.of(720, 576))
        assertEquals(Resolution.Sd, Resolution.of(null, null))
    }

    @Test
    fun `a single file needs no pick`() {
        assertEquals(hd, pickVersion(listOf(hd), VersionKey(Resolution.Uhd), null, Resolution.Uhd))
        assertNull(pickVersion(emptyList(), null, null, Resolution.Uhd))
    }

    @Test
    fun `the picked kind wins, by size when the range differs`() {
        assertEquals(hd, pickVersion(all, VersionKey(Resolution.Hd), setOf("uhd"), Resolution.Uhd))
        assertEquals(uhd, pickVersion(all, VersionKey(Resolution.Uhd, hdr = false), null, Resolution.FullHd))
    }

    @Test
    fun `a pick the title does not have falls back to the automatic choice`() {
        assertEquals(fullHd, pickVersion(listOf(fullHd, hd), VersionKey(Resolution.Uhd), null, Resolution.Uhd))
    }

    @Test
    fun `largest that plays as it is and fits the screen`() {
        assertEquals(uhd, pickVersion(all, null, setOf("uhd", "fhd", "hd"), Resolution.Uhd))
        assertEquals(fullHd, pickVersion(all, null, setOf("uhd", "fhd", "hd"), Resolution.FullHd))
        // The 2160p file would be converted on this device.
        assertEquals(fullHd, pickVersion(all, null, setOf("fhd", "hd"), Resolution.Uhd))
    }

    @Test
    fun `nothing fits the screen, the smallest that plays as it is`() {
        assertEquals(fullHd, pickVersion(all, null, setOf("uhd", "fhd"), Resolution.Hd))
    }

    @Test
    fun `nothing plays as it is or it is not known, the size alone decides`() {
        assertEquals(fullHd, pickVersion(all, null, emptySet(), Resolution.FullHd))
        assertEquals(uhd, pickVersion(all, null, null, Resolution.Uhd))
    }

    @Test
    fun `equal files keep the server's order`() {
        val other = version("fhd2", 1920, 1080)
        assertEquals(fullHd, pickVersion(listOf(fullHd, other), null, null, Resolution.Uhd))
    }

    @Test
    fun `the automatic choice never changes the cut`() {
        // A 1080p screen: the theatrical 1080p file, not one of the 1080p cuts.
        assertEquals(fullHd, pickVersion(cuts, null, null, Resolution.FullHd))
        assertEquals(uhd, pickVersion(cuts, null, null, Resolution.Uhd))
        // All named: the server's first cut.
        assertEquals(cut, pickVersion(listOf(cut, noir), null, null, Resolution.Uhd))
    }

    @Test
    fun `a movie keeps the very file picked`() {
        assertEquals(noir, pickVersion(cuts, VersionKey(Resolution.FullHd, edition = "Noir", id = "noir"), null, Resolution.Uhd))
        // Two 1080p cuts: the kind alone finds the right one too.
        assertEquals(cut, pickVersion(cuts, VersionKey(Resolution.FullHd, edition = "Director's Cut"), null, Resolution.Uhd))
    }

    @Test
    fun `a cut picked for a show stays the cut in every episode`() {
        val extended = version("ext", 3840, 2160, edition = "Extended")
        val extendedHd = version("exthd", 1920, 1080, edition = "Extended")
        val episode = listOf(uhd, extended, extendedHd)
        assertEquals(extendedHd, pickVersion(episode, VersionKey(Resolution.FullHd, edition = "Extended"), null, Resolution.Uhd))
        // This episode has the cut only in another size: still the cut.
        assertEquals(extended, pickVersion(listOf(uhd, extended), VersionKey(Resolution.FullHd, edition = "extended"), null, Resolution.Uhd))
    }

    @Test
    fun `sizes alone name the versions in lines`() {
        assertEquals(mapOf("uhd" to "2160p", "fhd" to "1080p", "hd" to "720p"), versionLabels(all, "Theatrical"))
        assertEquals(mapOf("dvd" to "576p", "fhd" to "1080p"), versionLabels(listOf(version("dvd", 720, 576), fullHd), "Theatrical"))
        val sdr = version("sdr", 3840, 2160)
        assertEquals(mapOf("uhd" to "2160p HDR", "sdr" to "2160p SDR"), versionLabels(listOf(uhd, sdr), "Theatrical"))
        val twin = version("twin", 1920, 1080)
        assertEquals(mapOf("fhd" to "1080p SDR 1", "twin" to "1080p SDR 2"), versionLabels(listOf(fullHd, twin), "Theatrical"))
    }

    @Test
    fun `cuts are named, the main one as theatrical, sizes only where a cut has several`() {
        assertEquals(
            mapOf("uhd" to "Theatrical 2160p", "dc" to "Director's Cut", "fhd" to "Theatrical 1080p", "noir" to "Noir"),
            versionLabels(cuts, "Theatrical"),
        )
        assertEquals(mapOf("fhd" to "Theatrical", "noir" to "Noir"), versionLabels(listOf(fullHd, noir), "Theatrical"))
    }

    @Test
    fun `the cut is what is left of the file's name`() {
        assertNull(editionOf("2160p", "Film"))
        assertNull(editionOf("4K HDR", "Film"))
        assertNull(editionOf("1080p Remux", "Film"))
        assertNull(editionOf("Glacier Editions (2026)", "Glacier Editions (2026)"))
        assertNull(editionOf(null, "Film"))
        assertEquals("Director's Cut", editionOf("Director's Cut", "Film"))
        assertEquals("Director's Cut", editionOf("Director's Cut 2160p", "Film"))
        assertEquals("Extended", editionOf("[Extended] - 1080p HEVC", "Film"))
        assertEquals("Noir", editionOf("Noir", "Logan"))
    }

    @Test
    fun `details follow the version, length included`() {
        val item = MediaItem(
            id = java.util.UUID.randomUUID(), kind = ItemKind.Movie, title = "T", sortName = null, year = null,
            communityRating = null, officialRating = null, runtimeMinutes = 120, runtimeMs = 7_200_000, genres = emptyList(), overview = null,
            parentTitle = null, seasonNumber = null, episodeNumber = null, progress = 0.5f, remainingMinutes = 60,
            resumePositionMs = 3_600_000, unwatchedCount = null, isFavorite = false, played = false, childCount = null, quality = null,
            posterUrl = null, thumbUrl = null, backdropUrl = null,
        )
        val details = ItemDetails(
            item = item, cast = emptyList(), tracks = null, trailers = Trailers(0, emptyList()),
            seriesId = null, seasonId = null, premiereDate = null, seasonCount = null, episodeCount = null,
            versions = cuts,
        )
        assertEquals(uhd.quality, details.forVersion("uhd").item.quality)
        val longer = details.forVersion("dc").item
        assertEquals(150, longer.runtimeMinutes)
        assertEquals(90, longer.remainingMinutes)
        assertEquals(0.4f, longer.progress!!, 0.001f)
        assertEquals(details, details.forVersion("missing"))
    }
}
