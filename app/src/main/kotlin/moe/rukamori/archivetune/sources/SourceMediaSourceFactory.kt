package moe.rukamori.archivetune.sources

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.IOException
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.metadata

internal class SourceMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val resolver: SourceRepository,
    private val sourceFactory: DataSource.Factory,
    private val youtubeFactory: DataSource.Factory,
    private val context: Context,
    private val cacheFactory: (DataSource.Factory, CacheKeyFactory) -> DataSource.Factory,
) : MediaSource.Factory by delegate {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val metadata = mediaItem.metadata
        val scheme = mediaItem.localConfiguration?.uri?.scheme

        if (!resolver.enabled || metadata == null || metadata.isPodcast ||
            scheme in setOf("file", "content", "android.resource", "http", "https")
        ) {
            return delegate.createMediaSource(mediaItem)
        }

        val request = SourceRequest(mediaItem.mediaId, metadata)
        val session = SourcePlaybackSession { resolver.resolve(request) }
        val factory = DataSource.Factory {
            SourceDataSource(
                session,
                { cachedSourceFactory(it) },
                youtubeFactory,
                context.getString(R.string.sources_error_connection),
                context.getString(R.string.sources_error_youtube),
            )
        }

        return ResolvingSourceMediaSource(mediaItem, session, context.getString(R.string.sources_error_resolution)) { stream ->
            val manifest = stream?.dashManifest

            if (manifest != null) {
                val dashFactory = cachedSourceFactory(stream, segmented = true)

                DashMediaSource.Factory(dashFactory)
                    .createMediaSource(manifest, mediaItem)
            } else {
                ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(mediaItem.buildUpon().setMimeType(null).build())
            }
        }
    }

    private fun cachedSourceFactory(stream: ResolvedSource, segmented: Boolean = false): DataSource.Factory = cacheFactory(
        DataSource.Factory { stream.decorateDataSource(sourceFactory.createDataSource()) },
        CacheKeyFactory { spec -> if (segmented) "${stream.cacheKey}:${spec.uri}" else stream.cacheKey },
    )
}

internal class SourcePlaybackSession(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val resolve: () -> ResolvedSource?,
) {
    private var resolved = false
    private var stream: ResolvedSource? = null
    private var resolvedAtMs = 0L

    @Synchronized
    fun stream(): ResolvedSource? {
        if (!resolved) {
            stream = resolve()
            resolvedAtMs = nowMs()
            resolved = true
        } else if (stream != null && nowMs() - resolvedAtMs > 240_000L) {
            stream = checkNotNull(stream).refresh()
            resolvedAtMs = nowMs()
        }

        return stream
    }
}

private class SourceDataSource(
    private val session: SourcePlaybackSession,
    private val sourceFactory: (ResolvedSource) -> DataSource.Factory,
    private val youtubeFactory: DataSource.Factory,
    private val sourceFailure: String,
    private val youtubeFailure: String,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null)

        val stream = session.stream()
        val selected = (if (stream == null) youtubeFactory else sourceFactory(stream)).createDataSource()

        listeners.forEach(selected::addTransferListener)
        delegate = selected

        val resolved = if (stream == null) {
            dataSpec
        } else {
            dataSpec.buildUpon()
                .setUri(stream.url.toUri())
                .setKey(stream.cacheKey)
                .setHttpRequestHeaders(emptyMap())
                .build()
        }

        try {
            return selected.open(resolved)
        } catch (failure: IOException) {
            throw IOException(if (stream == null) youtubeFailure else sourceFailure, failure)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(delegate).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

    override fun close() {
        val selected = delegate
        delegate = null
        selected?.close()
    }
}
