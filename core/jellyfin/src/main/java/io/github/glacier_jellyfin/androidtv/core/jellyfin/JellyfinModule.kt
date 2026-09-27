package io.github.glacier_jellyfin.androidtv.core.jellyfin

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import javax.inject.Singleton

/**
 * Provides the SDK entry point. The app module supplies [ClientInfo], since
 * only it knows the app's name and version.
 *
 * The SDK reads response bodies on the calling thread, so every API call must
 * run on Dispatchers.IO; large responses otherwise fail with
 * NetworkOnMainThreadException. Repositories switch dispatchers themselves.
 */
@Module
@InstallIn(SingletonComponent::class)
object JellyfinModule {

    @Provides
    @Singleton
    fun provideJellyfin(@ApplicationContext context: Context, clientInfo: ClientInfo): Jellyfin =
        createJellyfin {
            this.context = context
            this.clientInfo = clientInfo
        }
}
