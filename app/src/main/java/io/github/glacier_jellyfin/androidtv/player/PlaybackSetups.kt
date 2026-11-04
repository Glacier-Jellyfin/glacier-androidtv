package io.github.glacier_jellyfin.androidtv.player

import io.github.glacier_jellyfin.androidtv.core.data.settings.DolbyVisionProfile7Choice
import io.github.glacier_jellyfin.androidtv.core.data.settings.HdAudioOutput
import io.github.glacier_jellyfin.androidtv.core.data.settings.PlaybackSettings
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.player.DolbyVisionProfile7
import io.github.glacier_jellyfin.androidtv.core.player.PlaybackSetup

/**
 * How this device plays what it cannot take as it is: from what it decodes,
 * whether HDMI takes [iec61937] bursts right now, and the [playback] settings.
 */
fun DeviceCapabilities.playbackSetup(playback: PlaybackSettings, iec61937: Boolean): PlaybackSetup = PlaybackSetup(
    dolbyVisionProfile7 = when {
        decodesDolbyVisionProfile7 -> DolbyVisionProfile7.AsIs
        decodesDolbyVisionProfile8 && playback.dolbyVisionProfile7 == DolbyVisionProfile7Choice.DolbyVision -> DolbyVisionProfile7.Profile81
        else -> DolbyVisionProfile7.BaseLayer
    },
    iec61937 = iec61937 && playback.hdAudio != HdAudioOutput.Android,
)

/** Profile 7 can play either as Dolby Vision 8.1 or as HDR10 here, so the choice matters. */
val DeviceCapabilities.choosesDolbyVisionProfile7: Boolean
    get() = !decodesDolbyVisionProfile7 && decodesDolbyVisionProfile8
