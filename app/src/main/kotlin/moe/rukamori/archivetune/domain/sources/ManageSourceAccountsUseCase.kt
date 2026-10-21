package moe.rukamori.archivetune.domain.sources

import javax.inject.Inject
import moe.rukamori.archivetune.sources.deezer.DeezerQuality
import moe.rukamori.archivetune.sources.deezer.DeezerRepository
import moe.rukamori.archivetune.sources.qobuz.QobuzQuality
import moe.rukamori.archivetune.sources.qobuz.QobuzRepository
import moe.rukamori.archivetune.sources.tidal.TidalDeviceAuthorization
import moe.rukamori.archivetune.sources.tidal.TidalQuality
import moe.rukamori.archivetune.sources.tidal.TidalRepository

class ManageQobuzAccountUseCase @Inject constructor(private val repository: QobuzRepository) {
    val settings = repository.settings
    val storageState = repository.storageState

    suspend fun connect(appId: String, appSecret: String, token: String) {
        val id = appId.filterNot(Char::isWhitespace)
        val secret = appSecret.filterNot(Char::isWhitespace)
        val userToken = token.filterNot(Char::isWhitespace)
        require(id.isNotEmpty() && secret.isNotEmpty() && userToken.isNotEmpty())
        repository.connect(id, secret, userToken)
    }

    suspend fun setQuality(quality: QobuzQuality) = repository.updateSettings(quality)
    suspend fun disconnect() = repository.disconnect()
}

class ManageDeezerAccountUseCase @Inject constructor(private val repository: DeezerRepository) {
    val settings = repository.settings
    val storageState = repository.storageState

    suspend fun connect(token: String) {
        val arl = token.trim()
        require(arl.length in 64..512 && arl.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
        repository.connect(arl)
    }

    suspend fun setQuality(quality: DeezerQuality) = repository.updateSettings(quality)
    suspend fun disconnect() = repository.disconnect()
}

class ManageTidalAccountUseCase @Inject constructor(private val repository: TidalRepository) {
    val settings = repository.settings
    val storageState = repository.storageState

    internal suspend fun beginLogin(): TidalDeviceAuthorization = repository.beginLogin()
    internal suspend fun completeLogin(authorization: TidalDeviceAuthorization) = repository.completeLogin(authorization)
    suspend fun setQuality(quality: TidalQuality) = repository.updateSettings(quality)
    suspend fun disconnect() = repository.disconnect()
}
