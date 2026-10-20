package moe.rukamori.archivetune.sources.tidal

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

internal class TidalDataSource(private val delegate: DataSource) : DataSource by delegate {
    override fun open(dataSpec: DataSpec): Long {
        if (!TidalApi.isMediaUrl(dataSpec.uri.toString())) throw TidalException(TidalFailure.Unavailable)

        return delegate.open(dataSpec.buildUpon().setHttpRequestHeaders(emptyMap()).build())
    }
}
