/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modules

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class ModuleSettingsUseCases @Inject constructor(
    private val repository: ModuleRepository,
    private val diagnostics: ModuleDiagnostics,
) {
    val state = flow { repository.snapshot(); emitAll(repository.state.filterNotNull()) }
    val health = diagnostics.state
    suspend fun snapshot() = repository.snapshot()
    suspend fun setEnabled(value: Boolean) = repository.setEnabled(value)
    suspend fun enable(id: String, value: Boolean) = repository.setModuleEnabled(id, value)
    suspend fun reorder(ids: List<String>) = repository.reorder(ids)
    suspend fun remove(id: String, catalog: Boolean) = if (catalog) repository.removeCatalog(id) else repository.removeModule(id)
    suspend fun save(id: String, values: Map<String, String>) = repository.settings(id, values)
    suspend fun file(uri: Uri) = repository.importFile(uri)
    suspend fun url(value: String) = repository.importUrl(value)
    suspend fun catalog(value: String, id: String?) = repository.catalog(value, id)
    suspend fun refresh(id: String) = repository.refreshCatalog(id)
    suspend fun commit(value: ModuleImport) = repository.commit(value)

    suspend fun update(id: String): ModuleImport.Provider? {
        val module = repository.snapshot().modules.first { it.id == id }
        if (module.updateUrl.isBlank()) return null
        val proposed = repository.importUrl(module.updateUrl, id) as? ModuleImport.Provider
            ?: throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        return proposed.takeIf { it.document.source != module.source }
    }

    suspend fun check(id: String) {
        val module = repository.snapshot().modules.first { it.id == id }
        val started = System.currentTimeMillis()
        try {
            val checked = withTimeoutOrNull(15_000) { repository.check(module); true } == true
            diagnostics.record(id, ModuleDiagnostic(if (checked) ModuleCheckStatus.READY else ModuleCheckStatus.TIMEOUT,
                System.currentTimeMillis(), System.currentTimeMillis() - started))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val failure = (error as? ModuleException)?.failure ?: ModuleFailure.EXECUTION
            diagnostics.record(id, ModuleDiagnostic(when (failure) {
                ModuleFailure.INVALID_CONFIGURATION -> ModuleCheckStatus.CONFIGURATION
                ModuleFailure.NETWORK -> ModuleCheckStatus.UNAVAILABLE
                ModuleFailure.TIMEOUT -> ModuleCheckStatus.TIMEOUT
                else -> ModuleCheckStatus.FAILED
            },
                System.currentTimeMillis(), System.currentTimeMillis() - started, failure))
        }
    }
}

class RestoreModuleBackupUseCase @Inject constructor(private val repository: ModuleRepository) {
    suspend operator fun invoke(bytes: ByteArray) = repository.restorePortable(bytes)
}
