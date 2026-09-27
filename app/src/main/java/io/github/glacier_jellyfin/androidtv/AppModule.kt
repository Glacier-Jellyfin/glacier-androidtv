package io.github.glacier_jellyfin.androidtv

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.jellyfin.sdk.model.ClientInfo

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** How Glacier identifies itself to Jellyfin servers (shown in the server's device list). */
    @Provides
    fun provideClientInfo(): ClientInfo = ClientInfo(name = "Glacier", version = BuildConfig.VERSION_NAME)
}
