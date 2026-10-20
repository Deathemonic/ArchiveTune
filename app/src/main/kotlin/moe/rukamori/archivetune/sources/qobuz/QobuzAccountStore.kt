package moe.rukamori.archivetune.sources.qobuz

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import moe.rukamori.archivetune.sources.SourceStorageException
import org.json.JSONObject

@Singleton
class QobuzAccountStore
@Inject
constructor(
    @ApplicationContext context: Context,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "qobuz-account"))

    @Synchronized
    fun read(): Pair<QobuzCredentials?, QobuzSettings> {
        return run {
            val bytes = try {
                file.openRead().use { input ->
                    require(input.channel.size() in 29..65_536)
                    input.readBytes()
                }
            } catch (missing: FileNotFoundException) {
                if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return null to QobuzSettings()
                throw missing
            }
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            QobuzCredentials(
                requireNotNull((json.opt("appId") as? String)?.takeIf(String::isNotBlank)),
                requireNotNull((json.opt("appSecret") as? String)?.takeIf(String::isNotBlank)),
                requireNotNull((json.opt("userToken") as? String)?.takeIf(String::isNotBlank)),
            ) to
                QobuzSettings(
                    connected = true,
                    legacyYouTubeFallback = json.optBoolean("youtubeFallback", true),
                    quality = QobuzQuality.entries.firstOrNull { it.name == json.optString("quality") } ?: QobuzQuality.Master,
                )
        }
    }

    @Synchronized
    fun write(credentials: QobuzCredentials, settings: QobuzSettings) {
        try {
            val json = JSONObject()
                .put("appId", credentials.appId)
                .put("appSecret", credentials.appSecret)
                .put("userToken", credentials.userToken)
                .put("youtubeFallback", settings.legacyYouTubeFallback)
                .put("quality", settings.quality.name)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val output = file.startWrite()

            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        } catch (failure: Exception) {
            throw SourceStorageException(failure)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        if (file.baseFile.exists()) throw SourceStorageException(IOException("Unable to remove saved account"))
    }

    private fun key(create: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        if (!create) throw IOException("Saved account key is unavailable")

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "archivetune-qobuz"
    }
}
