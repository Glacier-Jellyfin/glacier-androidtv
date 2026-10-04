package io.github.glacier_jellyfin.androidtv.core.data.media

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import java.util.UUID

private const val TICKS_PER_MS = 10_000L
private const val TICKS_PER_MINUTE = 600_000_000L
private const val UHD_MIN_WIDTH = 3200

/** Widths requested from the server; images are scaled down there, not on the TV. */
private object ImageWidth {
    const val POSTER = 480
    const val THUMB = 720
    const val BACKDROP = 1920
    const val LIBRARY = 720
    const val COVER_SMALL = 160
    const val COVER_LARGE = 960
}

internal fun CollectionType?.toLibraryKind(): LibraryKind? = when (this) {
    CollectionType.MOVIES -> LibraryKind.Movies
    CollectionType.TVSHOWS -> LibraryKind.Shows
    CollectionType.MUSIC -> LibraryKind.Music
    CollectionType.MUSICVIDEOS -> LibraryKind.MusicVideos
    else -> null
}

internal class MediaMapper(private val api: ApiClient) {

    fun item(dto: BaseItemDto): MediaItem {
        val kind = when (dto.type) {
            BaseItemKind.MOVIE -> ItemKind.Movie
            BaseItemKind.SERIES -> ItemKind.Series
            BaseItemKind.EPISODE -> ItemKind.Episode
            BaseItemKind.MUSIC_ALBUM -> ItemKind.Album
            BaseItemKind.BOX_SET -> ItemKind.Collection
            BaseItemKind.GENRE -> ItemKind.Genre
            BaseItemKind.MUSIC_ARTIST -> ItemKind.Artist
            BaseItemKind.PLAYLIST -> ItemKind.Playlist
            BaseItemKind.MUSIC_VIDEO -> ItemKind.MusicVideo
            else -> ItemKind.Other
        }
        val runtimeTicks = dto.runTimeTicks
        val position = dto.userData?.playbackPositionTicks ?: 0
        val remaining = if (runtimeTicks != null && position > 0) ((runtimeTicks - position) / TICKS_PER_MINUTE).toInt() else null
        return MediaItem(
            id = dto.id,
            kind = kind,
            title = dto.name.orEmpty(),
            sortName = dto.sortName,
            year = dto.productionYear,
            communityRating = dto.communityRating,
            officialRating = dto.officialRating,
            runtimeMinutes = runtimeTicks?.let { (it / TICKS_PER_MINUTE).toInt() },
            genres = dto.genres.orEmpty(),
            overview = plainText(dto.overview),
            parentTitle = if (kind == ItemKind.Album) dto.albumArtist else dto.seriesName,
            seriesId = dto.seriesId,
            seasonNumber = dto.parentIndexNumber,
            episodeNumber = dto.indexNumber,
            progress = dto.userData?.playedPercentage?.let { (it / 100).toFloat() }?.takeIf { it > 0f },
            remainingMinutes = remaining,
            resumePositionMs = position / TICKS_PER_MS,
            unwatchedCount = dto.userData?.unplayedItemCount?.takeIf { kind == ItemKind.Series && it > 0 },
            isFavorite = dto.userData?.isFavorite ?: false,
            played = dto.userData?.played ?: false,
            childCount = when (kind) {
                ItemKind.Genre -> listOfNotNull(dto.movieCount, dto.seriesCount).sum().takeIf { it > 0 }
                ItemKind.Artist -> dto.albumCount
                else -> dto.childCount
            },
            quality = quality(dto),
            posterUrl = poster(dto),
            thumbUrl = thumb(dto),
            showThumbUrl = showThumb(dto) ?: thumb(dto),
            backdropUrl = backdrop(dto),
        )
    }

    fun track(dto: BaseItemDto): MusicTrack {
        val audio = dto.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO }
        return MusicTrack(
            id = dto.id,
            title = dto.name.orEmpty(),
            artist = dto.artists?.filter { it.isNotBlank() }?.joinToString(", ")?.ifEmpty { null } ?: dto.albumArtist,
            album = dto.album,
            albumId = dto.albumId,
            year = dto.productionYear,
            number = dto.indexNumber,
            disc = dto.parentIndexNumber,
            durationMs = (dto.runTimeTicks ?: 0) / TICKS_PER_MS,
            coverUrl = cover(dto, ImageWidth.COVER_SMALL),
            largeCoverUrl = cover(dto, ImageWidth.COVER_LARGE),
            format = audio?.let { AudioFormat(it.codec, it.sampleRate, it.bitDepth, it.channels) },
            isFavorite = dto.userData?.isFavorite ?: false,
            playlistItemId = dto.playlistItemId,
        )
    }

    private fun cover(dto: BaseItemDto, width: Int): String? {
        val albumId = dto.albumId
        val albumTag = dto.albumPrimaryImageTag
        if (albumId != null && albumTag != null) return image(albumId, ImageType.PRIMARY, albumTag, width)
        return dto.imageTags?.get(ImageType.PRIMARY)?.let { image(dto.id, ImageType.PRIMARY, it, width) }
    }

    fun library(dto: BaseItemDto, kind: LibraryKind, itemCount: Int?) = Library(
        id = dto.id,
        name = dto.name.orEmpty(),
        kind = kind,
        itemCount = itemCount,
        imageUrl = dto.imageTags?.get(ImageType.PRIMARY)?.let { image(dto.id, ImageType.PRIMARY, it, ImageWidth.LIBRARY) },
    )

    private fun quality(dto: BaseItemDto): VideoQuality? {
        val video = dto.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
        val hdr = when (video.videoRangeType) {
            VideoRangeType.HDR10 -> HdrFormat.Hdr10
            VideoRangeType.HDR10_PLUS -> HdrFormat.Hdr10Plus
            VideoRangeType.HLG -> HdrFormat.Hlg
            VideoRangeType.DOVI, VideoRangeType.DOVI_WITH_HDR10, VideoRangeType.DOVI_WITH_HLG,
            VideoRangeType.DOVI_WITH_SDR, VideoRangeType.DOVI_WITH_EL, VideoRangeType.DOVI_WITH_HDR10_PLUS,
            VideoRangeType.DOVI_WITH_ELHDR10_PLUS -> HdrFormat.DolbyVision
            else -> null
        }
        val uhd = (video.width ?: 0) >= UHD_MIN_WIDTH
        return VideoQuality(uhd, hdr).takeIf { uhd || hdr != null }
    }

    private fun poster(dto: BaseItemDto): String? {
        dto.imageTags?.get(ImageType.PRIMARY)?.let { return image(dto.id, ImageType.PRIMARY, it, ImageWidth.POSTER) }
        // Episodes without their own poster fall back to the series poster.
        val seriesId = dto.seriesId
        val seriesTag = dto.seriesPrimaryImageTag
        return if (seriesId != null && seriesTag != null) image(seriesId, ImageType.PRIMARY, seriesTag, ImageWidth.POSTER) else null
    }

    private fun thumb(dto: BaseItemDto): String? {
        // An episode's primary image is its own 16:9 still: more specific than any series art.
        if (dto.type == BaseItemKind.EPISODE) {
            dto.imageTags?.get(ImageType.PRIMARY)?.let { return image(dto.id, ImageType.PRIMARY, it, ImageWidth.THUMB) }
        }
        dto.imageTags?.get(ImageType.THUMB)?.let { return image(dto.id, ImageType.THUMB, it, ImageWidth.THUMB) }
        dto.backdropImageTags?.firstOrNull()?.let { return image(dto.id, ImageType.BACKDROP, it, ImageWidth.THUMB) }
        val parentThumb = dto.parentThumbItemId
        val parentThumbTag = dto.parentThumbImageTag
        if (parentThumb != null && parentThumbTag != null) return image(parentThumb, ImageType.THUMB, parentThumbTag, ImageWidth.THUMB)
        return parentBackdrop(dto, ImageWidth.THUMB)
    }

    /** For episodes, the show's own 16:9 art; null for everything else. */
    private fun showThumb(dto: BaseItemDto): String? {
        if (dto.type != BaseItemKind.EPISODE) return null
        val seriesId = dto.seriesId
        val seriesThumb = dto.seriesThumbImageTag
        if (seriesId != null && seriesThumb != null) return image(seriesId, ImageType.THUMB, seriesThumb, ImageWidth.THUMB)
        val parentThumb = dto.parentThumbItemId
        val parentThumbTag = dto.parentThumbImageTag
        if (parentThumb != null && parentThumbTag != null) return image(parentThumb, ImageType.THUMB, parentThumbTag, ImageWidth.THUMB)
        return parentBackdrop(dto, ImageWidth.THUMB)
    }

    private fun backdrop(dto: BaseItemDto): String? {
        dto.backdropImageTags?.firstOrNull()?.let { return image(dto.id, ImageType.BACKDROP, it, ImageWidth.BACKDROP) }
        return parentBackdrop(dto, ImageWidth.BACKDROP)
    }

    private fun parentBackdrop(dto: BaseItemDto, width: Int): String? {
        val parent = dto.parentBackdropItemId ?: return null
        val tag = dto.parentBackdropImageTags?.firstOrNull() ?: return null
        return image(parent, ImageType.BACKDROP, tag, width)
    }

    private fun image(id: UUID, type: ImageType, tag: String, maxWidth: Int) =
        api.imageApi.getItemImageUrl(itemId = id, imageType = type, tag = tag, maxWidth = maxWidth)
}
