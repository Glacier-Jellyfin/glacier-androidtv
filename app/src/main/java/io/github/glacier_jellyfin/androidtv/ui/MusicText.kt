package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.AudioFormat
import io.github.glacier_jellyfin.androidtv.core.data.media.channelLayout
import io.github.glacier_jellyfin.androidtv.core.data.media.codecName
import java.text.NumberFormat

/**
 * "FLAC · 24 Bit/96 kHz · Stereo" on music pages; the player's badge
 * ([short]) keeps codec and sample rate: "FLAC · 44,1 kHz".
 */
@Composable
fun audioFormatText(format: AudioFormat?, short: Boolean = false): String? {
    format ?: return null
    val locale = LocalConfiguration.current.locales[0]
    val rate = format.sampleRate?.takeIf { it > 0 }?.let {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it / 1000.0) + " kHz"
    }
    val bits = format.bitDepth?.takeIf { it > 0 }?.let { stringResource(R.string.audio_bit_depth, it) }
    val codec = codecName(format.codec)
    if (short) return listOfNotNull(codec, rate).joinToString(" · ").ifEmpty { null }
    val quality = listOfNotNull(bits, rate).joinToString("/").ifEmpty { null }
    val channels = when (format.channels) {
        null, 0 -> null
        1 -> stringResource(R.string.audio_mono)
        2 -> stringResource(R.string.audio_stereo)
        else -> channelLayout(format.channels)
    }
    return listOfNotNull(codec, quality, channels).joinToString(" · ").ifEmpty { null }
}
