package io.github.glacier_jellyfin.androidtv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.glacier_jellyfin.androidtv.core.data.geodata.AssetPackSource
import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoDataDebug
import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoDataManager
import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoDataSettings
import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoPacks
import io.github.glacier_jellyfin.androidtv.core.data.geodata.HttpPackSource
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivitySource
import io.github.glacier_jellyfin.androidtv.core.data.media.JellyfinEnhancedActivitySource
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSerializer
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsState
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAccountStore(@ApplicationContext context: Context): DataStore<AccountState> = DataStoreFactory.create(
        serializer = EncryptedJsonSerializer(KeystoreCipher(alias = "glacier.accounts")),
        corruptionHandler = ReplaceFileCorruptionHandler { AccountState() },
        produceFile = { File(context.filesDir, "datastore/accounts.bin") },
    )

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): DataStore<SettingsState> = DataStoreFactory.create(
        serializer = SettingsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { SettingsState() },
        produceFile = { File(context.filesDir, "datastore/settings.json") },
    )

    /** The activity feed comes from Jellyfin Enhanced. A companion plugin would be bound here instead. */
    @Provides
    @Singleton
    fun provideActivitySource(source: JellyfinEnhancedActivitySource): ActivitySource = source

    @Provides
    @Singleton
    fun provideGeoDataManager(
        @ApplicationContext context: Context,
        accounts: AccountRepository,
        debug: GeoDataDebug,
    ): GeoDataManager = GeoDataManager(
        dir = File(context.filesDir, "geodata"),
        settings = object : GeoDataSettings {
            override val level = accounts.state.map { GeoPacks.normalize(it.placeNamesLevel) }

            override suspend fun currentLevel(): Int? = level.first()

            override suspend fun setLevel(level: Int?) = accounts.setPlaceNamesLevel(level)
        },
        bundled = AssetPackSource(context),
        source = HttpPackSource { debug.baseUrl ?: GeoPacks.BASE_URL },
        packs = GeoPacks.ALL,
        checksumFromSource = { debug.baseUrl != null },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
}
