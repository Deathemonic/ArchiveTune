package moe.rukamori.archivetune.sources

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

data class SourceMatchReadResult(val tracks: Map<String, SourceTrack>, val invalidRecords: Int)

@Singleton
class SourceMatchStore
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private fun preferences(provider: SourceProvider) = context.getSharedPreferences("${provider.name.lowercase(Locale.ROOT)}_matches", Context.MODE_PRIVATE)

    fun read(provider: SourceProvider): SourceMatchReadResult {
        var invalidRecords = 0
        val tracks = preferences(provider).all.mapNotNull { (mediaId, value) ->
            try {
                require(mediaId.isNotBlank())
                val json = JSONObject(value as String)
                mediaId to SourceTrack(
                    id = json.getLong("id").also { require(it > 0) },
                    title = requireNotNull((json.opt("title") as? String)?.takeIf(String::isNotBlank)),
                    version = (json.opt("version") as? String)?.takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                    artist = (json.opt("artist") as? String)?.takeIf(String::isNotBlank).orEmpty(),
                    album = (json.opt("album") as? String)?.takeIf(String::isNotBlank).orEmpty(),
                    duration = json.getInt("duration").also { require(it >= 0) },
                    streamable = json.getBoolean("streamable"),
                    explicit = json.optBoolean("explicit"),
                    thumbnailUrl = json.optString("thumbnailUrl").takeIf { it.startsWith("https://") },
                )
            } catch (_: Exception) {
                invalidRecords++
                null
            }
        }.toMap()
        return SourceMatchReadResult(tracks, invalidRecords)
    }

    fun write(provider: SourceProvider, mediaId: String, track: SourceTrack?) {
        require(mediaId.isNotBlank())
        require(track == null || (track.id > 0 && track.title.isNotBlank() && track.duration >= 0))
        val editor = preferences(provider).edit()

        if (track == null) {
            editor.remove(mediaId)
        } else {
            val json = JSONObject()
                .put("id", track.id)
                .put("title", track.title)
                .put("version", track.version)
                .put("artist", track.artist)
                .put("album", track.album)
                .put("duration", track.duration)
                .put("streamable", track.streamable)
                .put("explicit", track.explicit)
                .put("thumbnailUrl", track.thumbnailUrl.orEmpty())
            editor.putString(mediaId, json.toString())
        }

        if (!editor.commit()) throw IOException("Unable to save source match")
    }
}
