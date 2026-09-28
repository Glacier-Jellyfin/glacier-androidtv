package io.github.glacier_jellyfin.androidtv.core.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/**
 * Switches Media3's audio and text tracks. Tracks inside the file are
 * addressed by their position among tracks of the same kind (the order the
 * server's stream indices have too); side-loaded subtitles by their id.
 */
object TrackControl {

    private const val EXTERNAL_PREFIX = "jellyfin-subtitle:"

    fun externalId(index: Int) = "$EXTERNAL_PREFIX$index"

    /** False while the tracks are not known yet (before the player is prepared). */
    fun selectAudio(player: Player, ordinal: Int): Boolean {
        val group = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(ordinal) ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        return true
    }

    fun selectEmbeddedText(player: Player, ordinal: Int): Boolean =
        selectText(player, textGroups(player).filterNot { it.isExternal() }.getOrNull(ordinal))

    fun selectExternalText(player: Player, index: Int): Boolean =
        selectText(player, textGroups(player).firstOrNull { it.getTrackFormat(0).id?.endsWith(externalId(index)) == true })

    /** No subtitles, also when the file marks one as default or forced. */
    fun disableText(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun selectText(player: Player, group: Tracks.Group?): Boolean {
        group ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        return true
    }

    private fun textGroups(player: Player) = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    // Media3 may put its own prefix in front of a side-loaded track's id, so match the end.
    private fun Tracks.Group.isExternal() = getTrackFormat(0).id?.contains(EXTERNAL_PREFIX) == true
}
