package moe.rukamori.archivetune.sources.qobuz

import moe.rukamori.archivetune.sources.SourceTrackMatcher

internal object QobuzTrackMatcher {
    fun match(query: QobuzSongQuery, tracks: List<QobuzTrack>): QobuzTrack? = SourceTrackMatcher.match(query, tracks)

    fun searchQueries(query: QobuzSongQuery): List<String> = SourceTrackMatcher.searchQueries(query)
}
