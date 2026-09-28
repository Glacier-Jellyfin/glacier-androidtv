package io.github.glacier_jellyfin.androidtv.core.jellyfin.playback

/**
 * Audio codecs the app decodes itself in software (the FFmpeg decoder), as
 * Jellyfin codec names. Added to the platform's decoders in the device profile,
 * so the server sends such tracks as they are instead of transcoding them.
 */
fun interface SoftwareAudioCodecs {
    fun codecs(): Set<String>
}
