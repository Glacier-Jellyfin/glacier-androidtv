package io.github.glacier_jellyfin.androidtv.channels

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.BasePreviewProgram
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import io.github.glacier_jellyfin.androidtv.MainActivity
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.UiLocale
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeScreenContent
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeScreenTitle
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.log.Log

/**
 * Writes [HomeScreenContent] to the Android TV home screen: "Watch next" and
 * three channels. The channels are created hidden; the user adds them in the
 * launcher's channel settings. Rows are updated in place rather than replaced,
 * so a title the user removed from a row stays removed while it is still in the
 * list ("Watch next" brings it back once it was watched further).
 *
 * Fire TV and other devices without the TV provider are skipped.
 */
// The program builders' public setters and getters sit on a library-restricted generic base class,
// which lint reports at every call although they are meant to be used.
@SuppressLint("RestrictedApi")
internal class HomeChannels(context: Context) {
    /** Channel names follow the app's own language, not the system's. */
    private val context = UiLocale.wrap(context.applicationContext)
    private val resolver = this.context.contentResolver
    private val helper = PreviewChannelHelper(this.context)

    val available: Boolean
        get() = context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

    /** Null content (nobody signed in) empties every row but keeps the channels and the user's choice of them. */
    fun write(content: HomeScreenContent?) {
        val channels = Channel.entries.associateWith { channelId(it) }
        for ((channel, id) in channels) {
            val programs = content?.let { owner ->
                val titles = channel.titles(owner)
                // Higher weights come first.
                titles.mapIndexed { index, title -> programFor(channel, title, owner, id, titles.size - index) }
            }
            writePrograms(id, programs.orEmpty())
        }
        writeWatchNext(content)
        Log.i(
            TAG,
            "Wrote ${content?.continueWatching?.size ?: 0} continue watching, ${content?.recentlyAdded?.size ?: 0} added, " +
                "${content?.recentReleases?.size ?: 0} releases",
        )
    }

    private fun channelId(channel: Channel): Long {
        val name = context.getString(channel.title)
        val existing = helper.getAllChannels().firstOrNull { it.internalProviderId == channel.key }
        if (existing != null) {
            if (existing.displayName?.toString() != name) {
                helper.updatePreviewChannel(existing.id, PreviewChannel.Builder(existing).setDisplayName(name).build())
            }
            return existing.id
        }
        val logo = checkNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)).toBitmap(LOGO_SIZE, LOGO_SIZE)
        return helper.publishChannel(
            PreviewChannel.Builder()
                .setInternalProviderId(channel.key)
                .setDisplayName(name)
                .setAppLinkIntent(Intent(context, MainActivity::class.java))
                .setLogo(logo)
                .build(),
        ).also { Log.i(TAG, "Created channel ${channel.key}") }
    }

    private fun programFor(channel: Channel, title: HomeScreenTitle, owner: HomeScreenContent, channelId: Long, weight: Int) =
        PreviewProgram.Builder()
            .setChannelId(channelId)
            .setWeight(weight)
            .describe(title, owner, wide = channel.wide)
            .build()

    private fun writePrograms(channelId: Long, programs: List<PreviewProgram>) {
        val existing = query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION, PreviewProgram::fromCursor)
            .associateBy { it.internalProviderId }
        for (program in programs) {
            val old = existing[program.internalProviderId]
            if (old == null) helper.publishPreviewProgram(program) else helper.updatePreviewProgram(old.id, program)
        }
        val wanted = programs.mapTo(HashSet()) { it.internalProviderId }
        existing.values.filterNot { it.internalProviderId in wanted }.forEach { helper.deletePreviewProgram(it.id) }
    }

    private fun writeWatchNext(content: HomeScreenContent?) {
        val existing = query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, WatchNextProgram::fromCursor)
            .associateBy { it.internalProviderId }
        val titles = content?.continueWatching.orEmpty()
        val now = System.currentTimeMillis()
        if (content != null) titles.forEachIndexed { index, title ->
            val old = existing[title.item.id.toString()]
            val builder = WatchNextProgram.Builder()
                .setWatchNextType(if (title.nextUp) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                // Next episodes have not been played; they keep the server's order behind the started titles.
                .setLastEngagementTimeUtcMillis(title.lastPlayedAt ?: (now - index * 1000L))
                .describe(title, content, wide = true)
            if (old == null) {
                helper.publishWatchNextProgram(builder.build())
            } else {
                // Removed by the user: back once it was watched further.
                if (!old.isBrowsable && old.lastPlaybackPositionMillis != title.item.resumePositionMs.toInt()) builder.setBrowsable(true)
                helper.updateWatchNextProgram(builder.build(), old.id)
            }
        }
        val wanted = titles.mapTo(HashSet()) { it.item.id.toString() }
        existing.values.filterNot { it.internalProviderId in wanted }
            .forEach { resolver.delete(TvContractCompat.buildWatchNextProgramUri(it.id), null, null) }
    }

    private fun <B : BasePreviewProgram.Builder<B>> B.describe(title: HomeScreenTitle, owner: HomeScreenContent, wide: Boolean): B {
        val item = title.item
        val id = item.id.toString()
        setInternalProviderId(id)
        setContentId(id)
        setIntent(HomeLaunch(owner.serverId, owner.userId, id).intent(context))
        when (item.kind) {
            ItemKind.Episode -> {
                setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                setTitle(item.parentTitle ?: item.title)
                setEpisodeTitle(item.title)
                item.seasonNumber?.let { setSeasonNumber(it) }
                item.episodeNumber?.let { setEpisodeNumber(it) }
            }
            ItemKind.Series -> setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES).setTitle(item.title)
            else -> setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE).setTitle(item.title)
        }
        item.overview?.let { setDescription(it) }
        item.year?.let { setReleaseDate(it.toString()) }
        title.durationMs?.let { setDurationMillis(it.toInt()) }
        if (item.resumePositionMs > 0) setLastPlaybackPositionMillis(item.resumePositionMs.toInt())
        val wideImage = item.thumbUrl ?: item.backdropUrl
        val poster = item.posterUrl
        if (wide && wideImage != null || poster == null) {
            wideImage?.let { setPosterArtUri(it.toUri()) }
            setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        } else {
            setPosterArtUri(poster.toUri())
            setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
        }
        return this
    }

    @SuppressLint("Recycle") // closed by use
    private fun <T> query(uri: Uri, projection: Array<String>, read: (android.database.Cursor) -> T): List<T> =
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(read(cursor)) }
        }.orEmpty()

    private enum class Channel(val key: String, @StringRes val title: Int, val wide: Boolean, val titles: (HomeScreenContent) -> List<HomeScreenTitle>) {
        ContinueWatching("continue", R.string.channel_continue_watching, wide = true, HomeScreenContent::continueWatching),
        RecentlyAdded("added", R.string.channel_recently_added, wide = false, HomeScreenContent::recentlyAdded),
        RecentReleases("releases", R.string.channel_recent_releases, wide = false, HomeScreenContent::recentReleases),
    }

    private companion object {
        const val TAG = "HomeChannels"
        const val LOGO_SIZE = 320
    }
}
