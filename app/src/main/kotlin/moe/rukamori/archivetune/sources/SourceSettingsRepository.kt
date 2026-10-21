package moe.rukamori.archivetune.sources

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import moe.rukamori.archivetune.sources.deezer.DeezerRepository
import moe.rukamori.archivetune.sources.qobuz.QobuzRepository
import moe.rukamori.archivetune.utils.dataStore

@Singleton
class SourceSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val legacyFallback: suspend () -> Boolean,
) {
    @Inject
    constructor(@ApplicationContext context: Context, qobuz: QobuzRepository, deezer: DeezerRepository) : this(
        context.dataStore,
        {
            qobuz.awaitInitialization()
            deezer.awaitInitialization()
            val qobuz = qobuz.settings.value
            val deezer = deezer.settings.value
            (!qobuz.connected || qobuz.legacyYouTubeFallback) && (!deezer.connected || deezer.legacyYouTubeFallback)
        },
    )

    val configuration = dataStore.data.map { preferences ->
        SourceConfiguration(
            priority = sourcePriority(preferences[PriorityKey]),
            youtubeFallback = preferences[YouTubeFallbackKey] ?: true,
        )
    }.onStart { migrateFallback() }.distinctUntilChanged()

    suspend fun setPriority(priority: List<SourceProvider>) {
        val value = sourcePriority(priority.joinToString(",") { it.name }).joinToString(",") { it.name }
        dataStore.edit { it[PriorityKey] = value }
    }

    suspend fun setYouTubeFallback(allowed: Boolean) {
        dataStore.edit { it[YouTubeFallbackKey] = allowed }
    }

    private suspend fun migrateFallback() {
        if (dataStore.data.first()[YouTubeFallbackKey] != null) return
        val fallback = legacyFallback()

        dataStore.edit { preferences ->
            if (preferences[YouTubeFallbackKey] == null) {
                preferences[YouTubeFallbackKey] = fallback
            }
        }
    }

    companion object {
        internal val PriorityKey = stringPreferencesKey("sourcePriority")
        internal val YouTubeFallbackKey = booleanPreferencesKey("sourceYouTubeFallback")
    }
}
