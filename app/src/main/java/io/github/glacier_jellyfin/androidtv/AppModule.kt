package io.github.glacier_jellyfin.androidtv

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.SoftwareAudioCodecs
import io.github.glacier_jellyfin.androidtv.core.player.FfmpegAudio
import io.github.glacier_jellyfin.androidtv.core.updater.ReleaseFeed
import io.github.glacier_jellyfin.androidtv.core.updater.UpdaterConfig
import org.jellyfin.sdk.model.ClientInfo

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** How Glacier identifies itself to Jellyfin servers (shown in the server's device list). */
    @Provides
    fun provideClientInfo(): ClientInfo = ClientInfo(name = "Glacier", version = BuildConfig.VERSION_NAME)

    /** Audio the bundled FFmpeg decoder plays, announced to the server with the platform's decoders. */
    @Provides
    fun provideSoftwareAudioCodecs(): SoftwareAudioCodecs = SoftwareAudioCodecs(FfmpegAudio::jellyfinCodecs)

    @Provides
    fun provideUpdaterConfig(): UpdaterConfig =
        UpdaterConfig(versionName = BuildConfig.VERSION_NAME, feedUrl = BuildConfig.UPDATE_FEED.ifEmpty { ReleaseFeed.GITHUB })
}
