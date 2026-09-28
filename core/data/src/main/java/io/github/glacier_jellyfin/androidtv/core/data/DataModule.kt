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
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSerializer
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsState
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAccountStore(@ApplicationContext context: Context): DataStore<AccountState> =
        DataStoreFactory.create(
            serializer = EncryptedJsonSerializer(KeystoreCipher(alias = "glacier.accounts")),
            corruptionHandler = ReplaceFileCorruptionHandler { AccountState() },
            produceFile = { File(context.filesDir, "datastore/accounts.bin") },
        )

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): DataStore<SettingsState> =
        DataStoreFactory.create(
            serializer = SettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { SettingsState() },
            produceFile = { File(context.filesDir, "datastore/settings.json") },
        )
}
