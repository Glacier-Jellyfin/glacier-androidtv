package io.github.glacier_jellyfin.androidtv.core.data.settings

import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentAction
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentPolicy
import io.github.glacier_jellyfin.androidtv.core.data.playback.UpNextMode
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.SubtitleBurnIn
import kotlinx.serialization.Serializable

/**
 * What one profile set on this device (Settings screen). Audio and subtitle
 * language preferences are not here: they live in the user's Jellyfin
 * configuration, see [ServerPreferences].
 */
@Serializable
data class ProfileSettings(
    val playback: PlaybackSettings = PlaybackSettings(),
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val home: HomeSettings = HomeSettings(),
    /** Tracks of the last title played, for "use the tracks of the last title". */
    val lastTracks: LastTracks = LastTracks(),
)

/** Settings › Appearance. */
@Serializable
data class AppearanceSettings(
    val accent: AccentColor = AccentColor.Crevasse,
    /** Row density "Compact": smaller cards, more of them per row. */
    val compact: Boolean = false,
    /** Focus without scaling, no Ken Burns and no animated scrolling. */
    val reduceMotion: Boolean = false,
    /** Movies of a collection show up in the library as one collection card. */
    val groupCollections: Boolean = true,
)

/** The design's five accents; the colours themselves live in the design system. */
@Serializable
enum class AccentColor { Crevasse, BlueIce, Aurora, PolarNight, Firn }

/** Settings › Home: what the spotlight at the top of the home screen shows. */
@Serializable
data class HomeSettings(
    val spotlightSource: SpotlightSource = SpotlightSource.ContinueWatching,
    val spotlightType: SpotlightType = SpotlightType.All,
    val spotlightCount: SpotlightCount = SpotlightCount.N5,
    val spotlightRotation: SpotlightRotation = SpotlightRotation.S9,
    /** Leaves out watched titles; does not apply to "Continue watching". */
    val spotlightUnwatched: Boolean = false,
)

@Serializable
enum class SpotlightSource { ContinueWatching, RecentlyAdded, Favorites, Random }

@Serializable
enum class SpotlightType { All, Movies, Shows }

@Serializable
enum class SpotlightCount(val count: Int) { N3(3), N5(5), N7(7), N10(10) }

/** Time until the spotlight moves on; [Off] stays on the first title. */
@Serializable
enum class SpotlightRotation(val seconds: Int) { Off(0), S6(6), S9(9), S15(15) }

@Serializable
data class PlaybackSettings(
    val upNext: UpNextChoice = UpNextChoice.WithCredits,
    val trailerAutoNext: Boolean = true,
    val seekBack: SeekStep = SeekStep.S10,
    val seekForward: SeekStep = SeekStep.S30,
    val maxBitrate: MaxBitrate = MaxBitrate.Auto,
    val segments: Map<SegmentKind, SegmentAction> = SegmentPolicy.DefaultActions,
    val audioChannels: AudioChannels = AudioChannels.Auto,
    val subtitleBurnIn: SubtitleBurnIn = SubtitleBurnIn.Auto,
) {
    val segmentPolicy: SegmentPolicy get() = SegmentPolicy(SegmentPolicy.DefaultActions + segments, upNext.mode)
}

/** When the "Up next" card appears (design options). */
@Serializable
enum class UpNextChoice(val mode: UpNextMode) {
    Off(UpNextMode.Off),
    WithCredits(UpNextMode.WithCredits),
    S10(UpNextMode.Before(10_000)),
    S15(UpNextMode.Before(15_000)),
    S20(UpNextMode.Before(20_000)),
    S30(UpNextMode.Before(30_000)),
    S40(UpNextMode.Before(40_000)),
    S50(UpNextMode.Before(50_000)),
    M1(UpNextMode.Before(60_000)),
}

/** Remote Left/Right, the OSD skip buttons and the music player's seek. */
@Serializable
enum class SeekStep(val ms: Long) {
    S5(5_000), S10(10_000), S15(15_000), S30(30_000), S60(60_000);

    companion object {
        val Back = listOf(S5, S10, S15, S30)
        val Forward = listOf(S10, S15, S30, S60)
    }
}

/** Cap for transcoded streams; direct play is not limited by it. */
@Serializable
enum class MaxBitrate(val bitsPerSecond: Int?) {
    Auto(null),
    M120(120_000_000),
    M80(80_000_000),
    M60(60_000_000),
    M40(40_000_000),
    M20(20_000_000),
    M10(10_000_000),
    M4(4_000_000),
    M1_5(1_500_000),
}

/** Most audio channels the server may send; more are downmixed. */
@Serializable
enum class AudioChannels(val max: Int?) {
    Auto(null), Stereo(2), Surround51(6), Surround71(8),
}

/** Look of text subtitles (not ASS, which brings its own styles). */
@Serializable
data class SubtitleStyle(
    val mode: SubtitleStyleMode = SubtitleStyleMode.Auto,
    val size: SubtitleSize = SubtitleSize.Small,
    val bold: Boolean = false,
    val font: SubtitleFont = SubtitleFont.Default,
    val color: SubtitleColor = SubtitleColor.White,
    val edge: SubtitleEdge = SubtitleEdge.None,
    val position: SubtitlePosition = SubtitlePosition.Bottom1,
)

@Serializable
enum class SubtitleStyleMode {
    /** Native when the Android caption settings are switched on, else custom. */
    Auto,
    Custom,
    Native,
}

/** Text height as a share of the picture height. */
@Serializable
enum class SubtitleSize(val fraction: Float) {
    XSmall(0.035f), Small(0.043f), Normal(0.0533f), Large(0.065f), XLarge(0.08f),
}

@Serializable
enum class SubtitleFont { Default, Serif, Monospace }

@Serializable
enum class SubtitleColor(val argb: Long) {
    White(0xFFFFFFFF), Yellow(0xFFFFE66D), IceBlue(0xFFCFE8FF), Grey(0xFFBFC6CE),
}

@Serializable
enum class SubtitleEdge { None, Raised, Depressed, Outline, Shadow }

/** Text line for subtitles without a position of their own: negative lines count from the bottom. */
@Serializable
enum class SubtitlePosition(val line: Int) {
    Bottom1(-1), Bottom2(-2), Bottom3(-3), Bottom5(-5), Top1(1),
}

/** What identifies a track across titles: language and kind, not the index. */
@Serializable
data class TrackKey(
    val language: String?,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    val title: String? = null,
)

@Serializable
data class LastTracks(
    val audio: TrackKey? = null,
    val subtitle: TrackKey? = null,
    /** Subtitles were switched off; [subtitle] is null then. */
    val subtitlesOff: Boolean = false,
)
