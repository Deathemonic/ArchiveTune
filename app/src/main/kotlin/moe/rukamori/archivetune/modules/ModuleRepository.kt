package moe.rukamori.archivetune.modules

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledModule(
    val id: String,
    val manifest: ModuleManifest,
    val source: String,
    val origin: String,
    val enabled: Boolean,
    val configuration: Map<String, String>,
    val revision: String,
    val updateUrl: String = origin,
)

data class SavedModuleCatalog(
    val id: String,
    val name: String,
    val url: String,
    val refreshedAtMs: Long,
    val entries: List<ModuleCatalogEntry>,
)

data class ModuleRegistry(
    val enabled: Boolean = false,
    val modules: List<InstalledModule> = emptyList(),
    val catalogs: List<SavedModuleCatalog> = emptyList(),
    val revision: String = UUID.randomUUID().toString(),
)

sealed interface ModuleImport {
    data class Provider(val document: ModuleDocument, val replacementId: String?) : ModuleImport
    data class Catalog(val catalog: SavedModuleCatalog) : ModuleImport
}

@Singleton
class ModuleRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val storage = AtomicFile(File(context.filesDir, "modules/registry.json"))
    private val mutex = Mutex()
    private val keyLock = Any()
    private val mutableState = MutableStateFlow<ModuleRegistry?>(null)
    val state = mutableState.asStateFlow()
    val http = ModuleHttpClient(ModuleNetworkPolicy.client(streaming = false))
    val runtime = ModuleRuntime(context, http)

    suspend fun snapshot(): ModuleRegistry = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableState.value ?: read().also { mutableState.value = it }
        }
    }

    suspend fun setEnabled(value: Boolean) = change { it.copy(enabled = value) }

    suspend fun setModuleEnabled(id: String, value: Boolean) = change { registry ->
        registry.copy(modules = registry.modules.map { if (it.id == id) it.copy(enabled = value) else it })
    }

    suspend fun reorder(ids: List<String>) = change { registry ->
        require(ids.size == registry.modules.size && ids.toSet() == registry.modules.map { it.id }.toSet())
        val modules = registry.modules.associateBy { it.id }
        registry.copy(modules = ids.map { modules.getValue(it) })
    }

    suspend fun removeModule(id: String) {
        change { it.copy(modules = it.modules.filterNot { module -> module.id == id }) }
        runtime.unload(id)
    }

    suspend fun removeCatalog(id: String) = change { it.copy(catalogs = it.catalogs.filterNot { catalog -> catalog.id == id }) }

    suspend fun settings(id: String, values: Map<String, String>) {
        change { registry ->
            registry.copy(modules = registry.modules.map { module ->
                if (module.id != id) module else {
                    val configuration = module.manifest.settings.associate { it.key to (values[it.key] ?: it.defaultJson) }
                    validateConfiguration(module.manifest, configuration)
                    module.copy(configuration = configuration, revision = UUID.randomUUID().toString())
                }
            })
        }
        runtime.unload(id)
    }

    suspend fun importFile(uri: Uri): ModuleImport = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readModuleBytes(ModuleSourceDecoder.MAX_SOURCE_BYTES) }
            ?: throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        if (bytes.size > ModuleSourceDecoder.MAX_SOURCE_BYTES) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        prepare(bytes.toString(Charsets.UTF_8), "", null)
    }

    suspend fun importUrl(url: String, replacementId: String? = null): ModuleImport {
        val (text, origin) = http.document(url.trim())
        val prepared = prepare(text, origin, replacementId)
        return if (prepared is ModuleImport.Provider) prepared.copy(document = prepared.document.copy(updateUrl = ModuleHttpClient.webUrl(url.trim()))) else prepared
    }

    suspend fun catalog(url: String, existingId: String? = null, initialText: String? = null): ModuleImport.Catalog {
        val existing = snapshot().catalogs.firstOrNull { it.id == existingId }
        val entries = LinkedHashMap<String, ModuleCatalogEntry>()
        val visited = HashSet<String>()
        suspend fun load(address: String, depth: Int) {
            if (depth > 4 || visited.size >= 32) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
            val normalized = if (address.isBlank() && depth == 0 && initialText != null) "file" else ModuleHttpClient.webUrl(address)
            if (!visited.add(normalized)) return
            val (text, finalUrl) = if (depth == 0 && initialText != null) initialText to url else http.document(normalized)
            val root = try { JSONObject(text) } catch (error: Exception) { throw ModuleException(ModuleFailure.INVALID_DOCUMENT, error) }
            fun append(array: JSONArray, base: String, providerCategory: Boolean = true) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val download = item.optString("download", item.optString("url")).takeIf(String::isNotBlank)
                        ?.let { ModuleHttpClient.webUrl(ModuleHttpClient.resolveUrl(it, base)) }
                    val id = item.optString("id").ifBlank { download.orEmpty() }
                    if (id.isBlank()) continue
                    val type = item.optString("type", "MODULE")
                    val supported = providerCategory && type.equals("MODULE", true) && download != null
                    val entry = ModuleCatalogEntry(id, item.optString("name", id), item.optString("version"),
                        item.optString("author"), item.optString("description"), download, supported,
                        (item.optJSONArray("labels") ?: item.optJSONArray("tags")).strings(), item.optJSONArray("capabilities").strings())
                    entries.putIfAbsent(download ?: "$base#$id", entry)
                    if (entries.size > 1000) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
                }
            }
            root.keys().forEach { key ->
                if (key.startsWith("category:") || key == "modules") {
                    root.optJSONArray(key)?.let { append(it, finalUrl, key !in listOf("category:artworks", "category:testing")) }
                }
            }
            val external = root.optJSONArray("external_sources") ?: JSONArray()
            for (index in 0 until external.length()) {
                val source = external.optJSONObject(index) ?: continue
                source.optJSONArray("modules")?.let {
                    val base = source.optString("baseUrl").takeIf(String::isNotBlank)
                        ?.let { value -> ModuleHttpClient.resolveUrl(value.trimEnd('/') + "/", finalUrl) } ?: finalUrl
                    append(it, base)
                }
                source.optString("url").takeIf(String::isNotBlank)?.let { load(ModuleHttpClient.resolveUrl(it, finalUrl), depth + 1) }
            }
            if (root.keys().asSequence().none { it.startsWith("category:") || it in listOf("modules", "external_sources") }) {
                throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
            }
        }
        load(url.trim(), 0)
        return ModuleImport.Catalog(SavedModuleCatalog(existing?.id ?: UUID.randomUUID().toString(),
            existing?.name ?: Uri.parse(url).host.orEmpty(), url.trim().takeIf(String::isNotBlank)?.let(ModuleHttpClient::webUrl).orEmpty(), System.currentTimeMillis(), entries.values.toList()))
    }

    suspend fun refreshCatalog(id: String) {
        val source = snapshot().catalogs.first { it.id == id }
        commit(catalog(source.url, id))
    }

    suspend fun commit(pending: ModuleImport) {
        when (pending) {
            is ModuleImport.Catalog -> change { registry ->
                val existing = registry.catalogs.indexOfFirst { it.id == pending.catalog.id || it.url == pending.catalog.url }
                registry.copy(catalogs = registry.catalogs.toMutableList().apply {
                    if (existing < 0) add(pending.catalog) else set(existing, pending.catalog.copy(id = get(existing).id))
                })
            }
            is ModuleImport.Provider -> {
                change { registry ->
                    val existing = registry.modules.firstOrNull { it.id == pending.replacementId }
                        ?: registry.modules.firstOrNull { it.manifest.id == pending.document.manifest.id &&
                            (it.origin == pending.document.origin || it.updateUrl == pending.document.updateUrl) }
                    if (existing != null && existing.manifest.id != pending.document.manifest.id) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
                    val manifest = pending.document.manifest
                    val configuration = manifest.settings.associate { field ->
                        field.key to (existing?.configuration?.get(field.key)?.takeIf { value -> validValue(field, value) } ?: field.defaultJson)
                    }
                    val module = InstalledModule(existing?.id ?: UUID.randomUUID().toString(), manifest, pending.document.source,
                        pending.document.origin, existing?.enabled ?: true, configuration, UUID.randomUUID().toString(), pending.document.updateUrl)
                    registry.copy(modules = registry.modules.toMutableList().apply {
                        if (existing == null) add(module) else set(indexOfFirst { it.id == existing.id }, module)
                    })
                }
                pending.replacementId?.let { runtime.unload(it) }
            }
        }
    }

    suspend fun check(module: InstalledModule) {
        validateConfiguration(module.manifest, module.configuration)
        runtime.inspect(module.source, module.origin)
    }

    suspend fun exportPortable(): ByteArray {
        val registry = snapshot()
        return withContext(Dispatchers.IO) { encode(registry, portable = true).toString().toByteArray() }
    }

    suspend fun restorePortable(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_REGISTRY_BYTES) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        val restored = decode(JSONObject(bytes.toString(Charsets.UTF_8)), portable = true)
        change(replacement = restored) { restored.copy(enabled = false, modules = restored.modules.map { it.copy(enabled = false) }) }
    }

    private suspend fun prepare(text: String, origin: String, replacementId: String?): ModuleImport {
        val trimmed = text.removePrefix("\uFEFF").trim()
        val json = if (trimmed.startsWith("{")) try { JSONObject(trimmed) } catch (_: org.json.JSONException) { null } else null
        if (json != null && json.keys().asSequence().any { it.startsWith("category:") || it in listOf("modules", "external_sources") }) {
            return catalog(origin, initialText = trimmed)
        }
        if (json != null) {
            val target = json.optString("download", json.optString("url"))
            if (target.isBlank()) throw ModuleException(ModuleFailure.UNSUPPORTED)
            val (source, address) = http.document(ModuleHttpClient.resolveUrl(target, origin))
            val manifest = runtime.inspect(source, address)
            return ModuleImport.Provider(ModuleDocument(source, address, manifest, origin.ifBlank { address }), replacementId)
        }
        val manifest = runtime.inspect(text, origin)
        return ModuleImport.Provider(ModuleDocument(text, origin, manifest), replacementId)
    }

    private suspend fun change(replacement: ModuleRegistry? = null, transform: (ModuleRegistry) -> ModuleRegistry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = replacement ?: mutableState.value ?: read()
            val next = transform(current).copy(revision = UUID.randomUUID().toString())
            if (next.modules.size > 256 || next.catalogs.size > 128) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
            val bytes = encode(next, portable = false).toString().toByteArray()
            if (bytes.size > MAX_REGISTRY_BYTES) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
            storage.baseFile.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw java.io.IOException("Module storage unavailable") }
            val output = storage.startWrite()
            try { output.write(bytes); storage.finishWrite(output) }
            catch (error: Throwable) { storage.failWrite(output); throw error }
            mutableState.value = next
        }
    }

    private fun read(): ModuleRegistry {
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return ModuleRegistry()
        val bytes = storage.openRead().use { it.readModuleBytes(MAX_REGISTRY_BYTES) }
        if (bytes.size > MAX_REGISTRY_BYTES) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        return decode(JSONObject(bytes.toString(Charsets.UTF_8)), portable = false)
    }

    internal fun encode(registry: ModuleRegistry, portable: Boolean): JSONObject = JSONObject()
        .put("version", 1).put("enabled", registry.enabled).put("revision", registry.revision)
        .put("modules", JSONArray().apply {
            registry.modules.forEach { module ->
                val settings = JSONObject()
                module.configuration.forEach { (key, value) ->
                    if (!portable || module.manifest.settings.none { it.key == key && it.secret }) settings.put(key, parseJsonValue(value))
                }
                put(JSONObject().put("id", module.id).put("manifest", manifestJson(module.manifest)).put("source", module.source)
                    .put("origin", module.origin).put("enabled", module.enabled).put("revision", module.revision)
                    .put("updateUrl", module.updateUrl)
                    .put("configuration", if (portable) settings else encrypt(settings.toString(), module.id)))
            }
        }).put("catalogs", JSONArray().apply {
            registry.catalogs.forEach { catalog ->
                put(JSONObject().put("id", catalog.id).put("name", catalog.name).put("url", catalog.url).put("refreshed", catalog.refreshedAtMs)
                    .put("entries", JSONArray().apply { catalog.entries.forEach { entry ->
                        put(JSONObject().put("id", entry.id).put("name", entry.name).put("version", entry.version).put("author", entry.author)
                            .put("description", entry.description).put("download", entry.downloadUrl).put("supported", entry.supported).put("labels", JSONArray(entry.labels))
                            .put("capabilities", JSONArray(entry.capabilities)))
                    } }))
            }
        })

    internal fun decode(root: JSONObject, portable: Boolean, allowRepeatedInstallations: Boolean = false): ModuleRegistry {
        if (root.getInt("version") != 1) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        val modules = root.getJSONArray("modules")
        val catalogs = root.getJSONArray("catalogs")
        if (modules.length() > (if (allowRepeatedInstallations) 2048 else 256) || catalogs.length() > 128) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        return ModuleRegistry(root.optBoolean("enabled"), (0 until modules.length()).map { index ->
            val item = modules.getJSONObject(index)
            val id = UUID.fromString(item.getString("id")).toString()
            val manifest = ModuleManifest.parse(item.getJSONObject("manifest"))
            val configuration = if (portable) item.getJSONObject("configuration") else JSONObject(decrypt(item.getString("configuration"), id))
            val values = manifest.settings.associate { field ->
                field.key to if (portable && field.secret) "null" else if (configuration.has(field.key)) jsonValue(configuration.get(field.key)) else field.defaultJson
            }
            val source = item.getString("source")
            if (source.length > ModuleSourceDecoder.MAX_SOURCE_BYTES) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
            InstalledModule(id, manifest, source, item.optString("origin"), item.optBoolean("enabled"), values, item.getString("revision"),
                item.optString("updateUrl", item.optString("origin")))
        }, (0 until catalogs.length()).map { index ->
            val item = catalogs.getJSONObject(index)
            val entries = item.getJSONArray("entries")
            SavedModuleCatalog(item.getString("id"), item.getString("name"), item.getString("url").takeIf(String::isNotBlank)?.let(ModuleHttpClient::webUrl).orEmpty(), item.optLong("refreshed"),
                (0 until entries.length()).map { position ->
                    val entry = entries.getJSONObject(position)
                    ModuleCatalogEntry(entry.getString("id"), entry.getString("name"), entry.optString("version"), entry.optString("author"),
                        entry.optString("description"), entry.optString("download").takeIf { it.isNotBlank() && it != "null" }, entry.optBoolean("supported"), entry.optJSONArray("labels").strings(),
                        entry.optJSONArray("capabilities").strings())
                })
        }, root.optString("revision", UUID.randomUUID().toString())).also { registry ->
            if ((!allowRepeatedInstallations && registry.modules.distinctBy { it.id }.size != registry.modules.size) ||
                registry.catalogs.distinctBy { it.id }.size != registry.catalogs.size) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        }
    }

    private fun manifestJson(manifest: ModuleManifest): JSONObject = JSONObject()
        .put("id", manifest.id).put("name", manifest.name).put("version", manifest.version).put("author", manifest.author)
        .put("description", manifest.description).put("labels", JSONArray(manifest.labels)).put("capabilities", JSONArray(manifest.capabilities))
        .put("settings", JSONObject().apply {
            manifest.settings.forEach { field -> put(field.key, JSONObject().put("label", field.label).put("description", field.description)
                .put("type", if (field.integerOnly) "integer" else field.type.name.lowercase(java.util.Locale.ROOT)).put("defaultValue", parseJsonValue(field.defaultJson)).put("required", field.required)
                .put("secret", field.secret).put("min", field.minimum).put("max", field.maximum)
                .put("options", JSONArray().apply { field.options.forEach { put(JSONObject().put("label", it.label).put("value", parseJsonValue(it.valueJson))) } })) }
        })

    private fun secretKey(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    internal fun encrypt(text: String, id: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(id.toByteArray())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    internal fun decrypt(text: String, id: String): String {
        val bytes = Base64.decode(text, Base64.DEFAULT)
        if (bytes.size < 28) throw ModuleException(ModuleFailure.INVALID_CONFIGURATION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(id.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    companion object {
        const val BACKUP_ENTRY = "modules.json"
        const val MAX_REGISTRY_BYTES = 32 * 1024 * 1024
        private const val KEY_ALIAS = "module_configuration_v1"

        fun validateConfiguration(manifest: ModuleManifest, values: Map<String, String>) {
            if (manifest.settings.any { !validValue(it, values[it.key] ?: it.defaultJson) }) throw ModuleException(ModuleFailure.INVALID_CONFIGURATION)
        }

        fun validValue(field: ModuleSetting, json: String): Boolean = try {
            val value = parseJsonValue(json)
            if (value == JSONObject.NULL) !field.required else if (field.required && value.toString().isBlank()) false else when (field.type) {
                ModuleSettingType.TEXT, ModuleSettingType.PASSWORD -> value is String
                ModuleSettingType.BOOLEAN -> value is Boolean
                ModuleSettingType.NUMBER -> value is Number && value.toDouble().isFinite() &&
                    (!field.integerOnly || value.toDouble() % 1.0 == 0.0) &&
                    (field.minimum == null || value.toDouble() >= field.minimum) && (field.maximum == null || value.toDouble() <= field.maximum)
                ModuleSettingType.SELECT -> field.options.any { equivalentJsonValue(it.valueJson, json) }
                ModuleSettingType.UNSUPPORTED -> !field.required
            }
        } catch (_: org.json.JSONException) { false }
    }
}
