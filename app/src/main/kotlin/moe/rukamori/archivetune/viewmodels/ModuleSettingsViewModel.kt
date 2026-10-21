/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.collect.ImmutableList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.modules.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.ArrayDeque
import java.util.Date
import javax.inject.Inject

@Immutable
sealed interface ModuleSettingsState {
    data object Loading : ModuleSettingsState
    data class Success(val model: ModuleSettingsUiModel) : ModuleSettingsState
    data class Empty(val model: ModuleSettingsUiModel) : ModuleSettingsState
    data class Error(@param:StringRes val message: Int) : ModuleSettingsState
}

enum class ModuleSettingsPage { HOME, MODULE, CATALOG }
enum class ModuleSettingsDialog { ADD, MODULE_URL, CATALOG_URL, REVIEW, REMOVE_MODULE, REMOVE_CATALOG, DISCARD }

@Immutable
data class ModuleRowUi(val id: String, val name: String, val subtitle: String, val enabled: Boolean,
    val position: Int, @param:StringRes val status: Int, val canUpdate: Boolean)

@Immutable
data class ModuleCatalogRowUi(val id: String, val name: String, val origin: String, val refreshed: String, val count: Int)

@Immutable
data class ModuleCatalogEntryUi(val key: String, val name: String, val subtitle: String, val description: String,
    val supported: Boolean, val installed: Boolean, val capabilities: ImmutableList<Int>, val download: String?)

@Immutable
data class ModuleOptionUi(val label: String, val json: String)

@Immutable
data class ModuleFieldUi(val key: String, val label: String, val description: String, val type: ModuleSettingType,
    val text: String, val json: String, val checked: Boolean, val sensitive: Boolean, val revealed: Boolean, @param:StringRes val error: Int?,
    val options: ImmutableList<ModuleOptionUi>)

@Immutable
data class ModuleDetailUi(val row: ModuleRowUi, val description: String, val origin: String,
    val capabilities: ImmutableList<Int>, val fields: ImmutableList<ModuleFieldUi>, val qualityDeclared: Boolean,
    val dirty: Boolean, val diagnosticTime: String?, val elapsedMs: Long, @param:StringRes val failure: Int?)

@Immutable
data class ModuleCatalogDetailUi(val row: ModuleCatalogRowUi, val entries: ImmutableList<ModuleCatalogEntryUi>)

@Immutable
data class ModuleReviewUi(val name: String, val version: String, val currentVersion: String?, val origin: String,
    val description: String, val capabilities: ImmutableList<Int>, val schemaChanged: Boolean, val catalogCount: Int?,
    val requiredSettings: ImmutableList<String>)

@Immutable
data class ModuleSettingsUiModel(val enabled: Boolean, val rows: ImmutableList<ModuleRowUi>, val catalogs: ImmutableList<ModuleCatalogRowUi>,
    val enabledCount: Int, val tab: Int, val page: ModuleSettingsPage, val module: ModuleDetailUi?, val catalog: ModuleCatalogDetailUi?,
    val dialog: ModuleSettingsDialog?, val url: String, val review: ModuleReviewUi?, val busy: String?,
    @param:StringRes val message: Int?, val messageIsError: Boolean, val menu: String?)

sealed interface ModuleSettingsAction {
    data object Retry : ModuleSettingsAction
    data class Tab(val index: Int) : ModuleSettingsAction
    data object Back : ModuleSettingsAction
    data object Add : ModuleSettingsAction
    data object File : ModuleSettingsAction
    data class FileSelected(val uri: Uri) : ModuleSettingsAction
    data class Url(val catalog: Boolean, val editId: String? = null) : ModuleSettingsAction
    data class UrlChanged(val value: String) : ModuleSettingsAction
    data object SubmitUrl : ModuleSettingsAction
    data object Dismiss : ModuleSettingsAction
    data object Confirm : ModuleSettingsAction
    data class SetEnabled(val value: Boolean) : ModuleSettingsAction
    data class Enable(val id: String, val value: Boolean) : ModuleSettingsAction
    data class OpenModule(val id: String) : ModuleSettingsAction
    data class OpenCatalog(val id: String) : ModuleSettingsAction
    data class Drag(val id: String, val target: String) : ModuleSettingsAction
    data object CommitOrder : ModuleSettingsAction
    data class Move(val id: String, val delta: Int) : ModuleSettingsAction
    data class Remove(val id: String, val catalog: Boolean) : ModuleSettingsAction
    data class Check(val id: String? = null) : ModuleSettingsAction
    data class Update(val id: String? = null) : ModuleSettingsAction
    data class Refresh(val id: String) : ModuleSettingsAction
    data class Install(val url: String) : ModuleSettingsAction
    data class Field(val key: String, val value: String) : ModuleSettingsAction
    data class Reveal(val key: String) : ModuleSettingsAction
    data object Save : ModuleSettingsAction
    data object CopyDiagnostics : ModuleSettingsAction
    data object ClearMessage : ModuleSettingsAction
    data class Menu(val id: String?) : ModuleSettingsAction
}

sealed interface ModuleSettingsEvent {
    data object PickFile : ModuleSettingsEvent
    data class Copy(val text: String) : ModuleSettingsEvent
    data object NavigateBack : ModuleSettingsEvent
}

@HiltViewModel
class ModuleSettingsViewModel @Inject constructor(private val useCases: ModuleSettingsUseCases) : ViewModel() {
    private val mutableState = MutableStateFlow<ModuleSettingsState>(ModuleSettingsState.Loading)
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<ModuleSettingsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var registry = ModuleRegistry()
    private var health: Map<String, ModuleDiagnostic> = emptyMap()
    private var observer: Job? = null
    private var operation: Job? = null
    private var tab = 0
    private var page = ModuleSettingsPage.HOME
    private var selectedId: String? = null
    private var dialog: ModuleSettingsDialog? = null
    private var targetId: String? = null
    private var url = ""
    private var busy: String? = null
    private var message: Int? = null
    private var messageIsError = false
    private var draft = emptyMap<String, String>()
    private var revealed = emptySet<String>()
    private var draftOrder: List<String>? = null
    private var pending: ModuleImport? = null
    private var menu: String? = null
    private val updateQueue = ArrayDeque<ModuleImport.Provider>()
    private var unsupportedDownloads = emptySet<String>()

    init { observe() }

    fun onAction(action: ModuleSettingsAction) {
        when (action) {
            ModuleSettingsAction.Retry -> observe()
            is ModuleSettingsAction.Tab -> { tab = action.index; render() }
            ModuleSettingsAction.Back -> back()
            ModuleSettingsAction.Add -> { dialog = ModuleSettingsDialog.ADD; render() }
            ModuleSettingsAction.File -> { dialog = null; eventChannel.trySend(ModuleSettingsEvent.PickFile); render() }
            is ModuleSettingsAction.Url -> {
                dialog = if (action.catalog) ModuleSettingsDialog.CATALOG_URL else ModuleSettingsDialog.MODULE_URL
                targetId = action.editId
                url = registry.catalogs.firstOrNull { it.id == action.editId }?.url.orEmpty()
                render()
            }
            is ModuleSettingsAction.UrlChanged -> { url = action.value; render() }
            ModuleSettingsAction.Dismiss -> {
                if (busy == null || dialog != ModuleSettingsDialog.REVIEW) {
                    operation?.cancel(); dialog = null; pending = null; updateQueue.clear(); render()
                }
            }
            is ModuleSettingsAction.OpenModule -> {
                selectedId = action.id; page = ModuleSettingsPage.MODULE
                draft = registry.modules.first { it.id == action.id }.configuration.toMap(); revealed = emptySet(); render()
            }
            is ModuleSettingsAction.OpenCatalog -> { selectedId = action.id; page = ModuleSettingsPage.CATALOG; render() }
            is ModuleSettingsAction.Drag -> {
                if (busy == null) {
                    val ids = (draftOrder ?: registry.modules.map { it.id }).toMutableList()
                    val from = ids.indexOf(action.id); val to = ids.indexOf(action.target)
                    if (from >= 0 && to >= 0) { ids.add(to, ids.removeAt(from)); draftOrder = ids; render() }
                }
            }
            is ModuleSettingsAction.Move -> {
                val ids = registry.modules.map { it.id }
                val from = ids.indexOf(action.id); val to = from + action.delta
                if (busy == null && from >= 0 && to in ids.indices) {
                    onAction(ModuleSettingsAction.Drag(action.id, ids[to])); onAction(ModuleSettingsAction.CommitOrder)
                }
            }
            is ModuleSettingsAction.Remove -> {
                targetId = action.id; dialog = if (action.catalog) ModuleSettingsDialog.REMOVE_CATALOG else ModuleSettingsDialog.REMOVE_MODULE; render()
            }
            is ModuleSettingsAction.Field -> {
                val field = registry.modules.firstOrNull { it.id == selectedId }?.manifest?.settings?.firstOrNull { it.key == action.key } ?: return
                val value = when (field.type) {
                    ModuleSettingType.NUMBER, ModuleSettingType.BOOLEAN, ModuleSettingType.SELECT -> action.value
                    else -> JSONObject.quote(action.value)
                }
                draft = draft + (action.key to value); render()
            }
            is ModuleSettingsAction.Reveal -> { revealed = if (action.key in revealed) revealed - action.key else revealed + action.key; render() }
            ModuleSettingsAction.ClearMessage -> { message = null; render() }
            is ModuleSettingsAction.Menu -> { menu = action.id; render() }
            ModuleSettingsAction.CopyDiagnostics -> copyDiagnostics()
            else -> mutate(action)
        }
    }

    private fun observe() {
        observer?.cancel()
        mutableState.value = ModuleSettingsState.Loading
        observer = viewModelScope.launch {
            try {
                combine(useCases.state, useCases.health) { data, checks -> data to checks }.collect { (data, checks) ->
                    registry = data; health = checks; render()
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = ModuleSettingsState.Error(R.string.modules_load_failed) }
        }
    }

    private fun mutate(action: ModuleSettingsAction) {
        if (operation?.isActive == true) return
        if (action == ModuleSettingsAction.CommitOrder && draftOrder == null) return
        busy = when (action) {
            is ModuleSettingsAction.Check -> action.id ?: "all"
            is ModuleSettingsAction.Update -> action.id ?: "all"
            is ModuleSettingsAction.Refresh -> action.id
            else -> "all"
        }
        message = null
        val refreshDraft = action == ModuleSettingsAction.Save || (action == ModuleSettingsAction.Confirm && dialog == ModuleSettingsDialog.REVIEW)
        render()
        operation = viewModelScope.launch {
            try {
                when (action) {
                    is ModuleSettingsAction.FileSelected -> pending = useCases.file(action.uri)
                    ModuleSettingsAction.SubmitUrl -> pending = if (dialog == ModuleSettingsDialog.CATALOG_URL) useCases.catalog(url, targetId) else useCases.url(url)
                    is ModuleSettingsAction.Install -> {
                        val existing = registry.modules.firstOrNull { it.origin == action.url || it.updateUrl == action.url }
                        pending = if (existing == null) useCases.url(action.url) else useCases.update(existing.id)
                        if (pending == null) message = R.string.modules_no_updates
                    }
                    is ModuleSettingsAction.SetEnabled -> useCases.setEnabled(action.value)
                    is ModuleSettingsAction.Enable -> useCases.enable(action.id, action.value)
                    ModuleSettingsAction.CommitOrder -> draftOrder?.let { useCases.reorder(it) }
                    ModuleSettingsAction.Save -> {
                        val id = selectedId ?: return@launch
                        useCases.save(id, draft); message = R.string.modules_saved
                    }
                    is ModuleSettingsAction.Refresh -> useCases.refresh(action.id)
                    is ModuleSettingsAction.Check -> {
                        val ids = action.id?.let(::listOf) ?: registry.modules.filter { it.enabled }.map { it.id }
                        ids.forEach { useCases.check(it) }
                    }
                    is ModuleSettingsAction.Update -> {
                        val ids = action.id?.let(::listOf) ?: registry.modules.filter { it.updateUrl.isNotBlank() }.map { it.id }
                        ids.forEach { id -> useCases.update(id)?.let { updateQueue.add(it) } }
                        pending = updateQueue.pollFirst()
                        if (pending == null) message = R.string.modules_no_updates
                    }
                    ModuleSettingsAction.Confirm -> when (dialog) {
                        ModuleSettingsDialog.REVIEW -> {
                            pending?.let { useCases.commit(it) }; pending = updateQueue.pollFirst()
                            dialog = null
                        }
                        ModuleSettingsDialog.REMOVE_MODULE, ModuleSettingsDialog.REMOVE_CATALOG -> {
                            val id = targetId ?: return@launch
                            useCases.remove(id, dialog == ModuleSettingsDialog.REMOVE_CATALOG)
                            if (selectedId == id) { selectedId = null; page = ModuleSettingsPage.HOME }
                            dialog = null
                        }
                        ModuleSettingsDialog.DISCARD -> { draft = emptyMap(); selectedId = null; page = ModuleSettingsPage.HOME; dialog = null }
                        else -> Unit
                    }
                    else -> Unit
                }
                registry = useCases.snapshot()
                if (refreshDraft) draft = registry.modules.firstOrNull { it.id == selectedId }?.configuration.orEmpty().toMap()
                if (pending != null) dialog = ModuleSettingsDialog.REVIEW
                messageIsError = false
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (action is ModuleSettingsAction.Install && (error as? ModuleException)?.failure == ModuleFailure.UNSUPPORTED) unsupportedDownloads += action.url
                message = if (action == ModuleSettingsAction.CommitOrder) R.string.modules_order_failed else failureResource(error)
                messageIsError = true
            }
            finally { draftOrder = null; busy = null; render() }
        }
    }

    private fun back() {
        if (busy != null) {
            if (dialog != ModuleSettingsDialog.REVIEW) operation?.cancel()
            return
        }
        if (dialog != null) { onAction(ModuleSettingsAction.Dismiss); return }
        val module = registry.modules.firstOrNull { it.id == selectedId }
        if (page == ModuleSettingsPage.MODULE && module != null && draft != module.configuration) {
            dialog = ModuleSettingsDialog.DISCARD; render(); return
        }
        if (page != ModuleSettingsPage.HOME) { page = ModuleSettingsPage.HOME; selectedId = null; draft = emptyMap(); revealed = emptySet(); render() }
        else eventChannel.trySend(ModuleSettingsEvent.NavigateBack)
    }

    private fun render() {
        val modules = draftOrder?.mapNotNull { id -> registry.modules.firstOrNull { it.id == id } } ?: registry.modules
        val rows = modules.mapIndexed { index, module -> ModuleRowUi(module.id, module.manifest.name,
            listOf(module.manifest.author, module.manifest.version).filter(String::isNotBlank).joinToString(" · "),
            module.enabled, index + 1, if (busy == module.id) R.string.modules_checking else statusResource(health[module.id]?.status), module.updateUrl.isNotBlank()) }
        val catalogs = registry.catalogs.map { ModuleCatalogRowUi(it.id, it.name, safeOrigin(it.url), date(it.refreshedAtMs), it.entries.size) }
        val selected = modules.firstOrNull { it.id == selectedId }
        val module = selected?.let { item ->
            val diagnostic = health[item.id]
            ModuleDetailUi(rows.first { it.id == item.id }, item.manifest.description, safeOrigin(item.origin), capabilities(item.manifest.capabilities),
                ImmutableList.copyOf(item.manifest.settings.map { field ->
                    val value = draft[field.key] ?: field.defaultJson
                    val parsed = try { parseJsonValue(value) } catch (_: Exception) { null }
                    val valid = ModuleRepository.validValue(field, value)
                    ModuleFieldUi(field.key, field.label, field.description, field.type,
                        if (parsed is String) parsed else if (parsed == JSONObject.NULL) "" else if (parsed == null) value else parsed.toString(),
                        field.options.firstOrNull { equivalentJsonValue(it.valueJson, value) }?.valueJson ?: value,
                        parsed == true, field.secret, field.key in revealed,
                        if (valid) null else if (field.required && (parsed == "" || parsed == JSONObject.NULL)) R.string.modules_field_required else R.string.modules_field_invalid,
                        ImmutableList.copyOf(field.options.map { ModuleOptionUi(it.label, it.valueJson) }))
                }), item.manifest.settings.any { it.key.contains("quality", true) }, draft != item.configuration,
                diagnostic?.let { date(it.atMs) }, diagnostic?.elapsedMs ?: 0, diagnostic?.failure?.let { failureResource(ModuleException(it)) })
        }
        val catalog = registry.catalogs.firstOrNull { it.id == selectedId }?.let { item ->
            ModuleCatalogDetailUi(catalogs.first { it.id == item.id }, ImmutableList.copyOf(item.entries.map { entry ->
                ModuleCatalogEntryUi(entry.downloadUrl ?: entry.id, entry.name, listOf(entry.author, entry.version).filter(String::isNotBlank).joinToString(" · "),
                    entry.description, entry.supported && entry.downloadUrl !in unsupportedDownloads, registry.modules.any { it.origin == entry.downloadUrl || it.updateUrl == entry.downloadUrl },
                    capabilities(entry.capabilities), entry.downloadUrl)
            }))
        }
        val review = when (val value = pending) {
            is ModuleImport.Provider -> {
                val existing = registry.modules.firstOrNull { it.id == value.replacementId }
                    ?: registry.modules.firstOrNull { it.manifest.id == value.document.manifest.id && it.origin == value.document.origin }
                ModuleReviewUi(value.document.manifest.name, value.document.manifest.version, existing?.manifest?.version,
                    safeOrigin(value.document.origin), value.document.manifest.description, capabilities(value.document.manifest.capabilities),
                    existing != null && existing.manifest.settings != value.document.manifest.settings, null,
                    ImmutableList.copyOf(value.document.manifest.settings.filter { it.required }.map { it.label }))
            }
            is ModuleImport.Catalog -> ModuleReviewUi(value.catalog.name, "", null, safeOrigin(value.catalog.url), "", ImmutableList.of(), false, value.catalog.entries.size, ImmutableList.of())
            null -> null
        }
        val model = ModuleSettingsUiModel(registry.enabled, ImmutableList.copyOf(rows), ImmutableList.copyOf(catalogs), modules.count { it.enabled },
            tab, page, module, catalog, dialog, url, review, busy, message, messageIsError, menu)
        mutableState.value = if (modules.isEmpty() && registry.catalogs.isEmpty() && page == ModuleSettingsPage.HOME) ModuleSettingsState.Empty(model) else ModuleSettingsState.Success(model)
    }

    private fun copyDiagnostics() {
        val entries = registry.modules.filter { selectedId == null || it.id == selectedId }
        val text = entries.joinToString("\n") { module ->
            val check = health[module.id]
            "${module.manifest.id} ${module.manifest.version}: ${check?.status?.name ?: "NOT_CHECKED"}; elapsedMs=${check?.elapsedMs ?: 0}; failure=${check?.failure?.name.orEmpty()}"
        }
        eventChannel.trySend(ModuleSettingsEvent.Copy(text))
    }

    private fun date(millis: Long): String = if (millis > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis)) else ""

    companion object {
        private fun safeOrigin(url: String): String = Uri.parse(url).host.orEmpty()
        private fun capabilities(values: List<String>): ImmutableList<Int> = ImmutableList.copyOf(values.mapNotNull {
            when (it) {
                "searchTracks" -> R.string.modules_capability_search
                "getTrackStreamUrl" -> R.string.modules_capability_stream
                "getAlbum" -> R.string.modules_capability_album
                "getArtist" -> R.string.modules_capability_artist
                "getPlaylist" -> R.string.modules_capability_playlist
                else -> null
            }
        })

        private fun statusResource(status: ModuleCheckStatus?): Int = when (status) {
            null -> R.string.modules_not_checked
            ModuleCheckStatus.READY -> R.string.modules_ready
            ModuleCheckStatus.CONFIGURATION -> R.string.modules_needs_configuration
            ModuleCheckStatus.UNAVAILABLE -> R.string.modules_unavailable
            ModuleCheckStatus.FAILED -> R.string.modules_failed
            ModuleCheckStatus.NO_MATCH -> R.string.modules_no_match
            ModuleCheckStatus.RESOLVED -> R.string.modules_resolved
            ModuleCheckStatus.TIMEOUT -> R.string.modules_timeout
        }

        private fun failureResource(error: Exception): Int = when ((error as? ModuleException)?.failure) {
            ModuleFailure.INVALID_DOCUMENT -> R.string.modules_invalid_document
            ModuleFailure.UNSUPPORTED -> R.string.modules_unsupported_description
            ModuleFailure.INTEGRITY -> R.string.modules_integrity_failed
            ModuleFailure.NETWORK -> R.string.modules_network_failed
            ModuleFailure.INVALID_CONFIGURATION -> R.string.modules_configuration_failed
            ModuleFailure.TIMEOUT -> R.string.modules_timeout
            ModuleFailure.EXECUTION -> R.string.modules_execution_failed
            null -> R.string.modules_action_failed
        }
    }
}
