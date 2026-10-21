package moe.rukamori.archivetune.sources

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.Loader
import java.io.IOException

internal class ResolvingSourceMediaSource(
    private val item: MediaItem,
    private val session: SourcePlaybackSession,
    private val failureMessage: String,
    private val createSource: (ResolvedSource?) -> MediaSource,
) : CompositeMediaSource<Unit>() {
    private var loader: Loader? = null
    private var child: MediaSource? = null

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)

        val loader = Loader("SourceResolver")
        this.loader = loader

        loader.startLoading(
            Resolution(),
            object : Loader.Callback<Resolution> {
                override fun onLoadCompleted(loadable: Resolution, elapsedRealtimeMs: Long, loadDurationMs: Long) {
                    val source = checkNotNull(loadable.source)

                    child = source
                    prepareChildSource(Unit, source)
                }

                override fun onLoadCanceled(loadable: Resolution, elapsedRealtimeMs: Long, loadDurationMs: Long, released: Boolean) = Unit

                override fun onLoadError(
                    loadable: Resolution,
                    elapsedRealtimeMs: Long,
                    loadDurationMs: Long,
                    error: IOException,
                    errorCount: Int,
                ): Loader.LoadErrorAction = Loader.DONT_RETRY_FATAL
            },
            0,
        )
    }

    override fun maybeThrowSourceInfoRefreshError() {
        loader?.maybeThrowError()
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(id: Unit, mediaSource: MediaSource, timeline: Timeline) {
        refreshSourceInfo(timeline)
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod = checkNotNull(child).createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        checkNotNull(child).releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        loader?.release()
        loader = null
        super.releaseSourceInternal()
        child = null
    }

    private inner class Resolution : Loader.Loadable {
        @Volatile private var canceled = false

        @Volatile private var loadingThread: Thread? = null

        var source: MediaSource? = null
            private set

        override fun cancelLoad() {
            canceled = true
            loadingThread?.interrupt()
        }

        override fun load() {
            loadingThread = Thread.currentThread()

            try {
                if (canceled) return

                val stream = session.stream()

                if (!canceled) source = createSource(stream)
            } catch (failure: Exception) {
                if (!canceled) throw IOException(failureMessage, failure)
            } finally {
                loadingThread = null
                Thread.interrupted()
            }
        }
    }
}
