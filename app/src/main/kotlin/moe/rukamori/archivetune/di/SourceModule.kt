package moe.rukamori.archivetune.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.App
import moe.rukamori.archivetune.sources.DefaultSourceRepository
import moe.rukamori.archivetune.sources.PlaybackSource
import moe.rukamori.archivetune.sources.SourceRepository
import moe.rukamori.archivetune.sources.deezer.DeezerPlaybackSource
import moe.rukamori.archivetune.sources.qobuz.QobuzPlaybackSource
import moe.rukamori.archivetune.sources.tidal.TidalPlaybackSource

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SourceApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class SourceModule {
    @Binds
    abstract fun bindSourceRepository(repository: DefaultSourceRepository): SourceRepository

    @Binds
    @IntoSet
    abstract fun bindQobuzSource(source: QobuzPlaybackSource): PlaybackSource

    @Binds
    @IntoSet
    abstract fun bindDeezerSource(source: DeezerPlaybackSource): PlaybackSource

    @Binds
    @IntoSet
    abstract fun bindTidalSource(source: TidalPlaybackSource): PlaybackSource

    companion object {
        @Provides
        @Singleton
        @SourceApplicationScope
        fun provideSourceScope(@ApplicationContext context: Context): CoroutineScope = (context.applicationContext as App).applicationScope
    }
}
