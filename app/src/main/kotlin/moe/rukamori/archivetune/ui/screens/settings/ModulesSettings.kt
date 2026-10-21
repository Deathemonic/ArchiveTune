package moe.rukamori.archivetune.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.modules.ModuleSettingType
import moe.rukamori.archivetune.viewmodels.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun ModulesSettings(navController: NavController, viewModel: ModuleSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onAction = remember(viewModel) { viewModel::onAction }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onAction(ModuleSettingsAction.FileSelected(it)) }
    }
    val clipboardLabel = stringResource(R.string.modules_diagnostics)
    LaunchedEffect(viewModel, context, clipboardLabel) {
        viewModel.events.collect { event -> when (event) {
            ModuleSettingsEvent.PickFile -> picker.launch(arrayOf("*/*"))
            ModuleSettingsEvent.NavigateBack -> navController.navigateUp()
            is ModuleSettingsEvent.Copy -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(clipboardLabel, event.text))
        } }
    }
    BackHandler { onAction(ModuleSettingsAction.Back) }
    ModuleSettingsContent(state, onAction)
}

@Composable
private fun ModuleSettingsContent(state: ModuleSettingsState, onAction: (ModuleSettingsAction) -> Unit) {
    val model = when (state) {
        is ModuleSettingsState.Success -> state.model
        is ModuleSettingsState.Empty -> state.model
        else -> null
    }
    val onBack = remember(onAction) { { onAction(ModuleSettingsAction.Back) } }
    val onAdd = remember(onAction) { { onAction(ModuleSettingsAction.Add) } }
    Scaffold(topBar = {
        TopAppBar(title = { Text(model?.module?.row?.name?.takeIf { model.page == ModuleSettingsPage.MODULE }
            ?: model?.catalog?.row?.name?.takeIf { model.page == ModuleSettingsPage.CATALOG && it.isNotBlank() }
            ?: stringResource(R.string.modules_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.modules_back)) } },
            actions = {
                if (model != null && model.page == ModuleSettingsPage.HOME) {
                    IconButton(onClick = onAdd, enabled = model.busy == null) { Icon(painterResource(R.drawable.add), stringResource(R.string.add)) }
                    ModuleMenu(model.menu == "toolbar", model.busy == null,
                        onOpen = remember(onAction) { { onAction(ModuleSettingsAction.Menu("toolbar")) } },
                        onDismiss = remember(onAction) { { onAction(ModuleSettingsAction.Menu(null)) } }) {
                        ModuleMenuItem(R.string.modules_check_all, model.rows.any { it.enabled }, onAction, ModuleSettingsAction.Check())
                        ModuleMenuItem(R.string.modules_check_updates, model.rows.any { it.canUpdate }, onAction, ModuleSettingsAction.Update())
                    }
                }
            })
    }) { padding ->
        val insets = LocalPlayerAwareWindowInsets.current
        val contentModifier = remember(padding, insets) {
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
                .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        }
        Box(contentModifier, contentAlignment = Alignment.TopCenter) {
            when (state) {
                ModuleSettingsState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ModuleSettingsState.Error -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(state.message))
                    TextButton(onClick = remember(onAction) { { onAction(ModuleSettingsAction.Retry) } }) { Text(stringResource(R.string.retry)) }
                }
                else -> if (model != null) {
                    when (model.page) {
                        ModuleSettingsPage.HOME -> ModuleHome(model, onAction)
                        ModuleSettingsPage.MODULE -> model.module?.let { ModuleDetails(it, model, onAction) }
                        ModuleSettingsPage.CATALOG -> model.catalog?.let { ModuleCatalogDetails(it, model, onAction) }
                    }
                    ModuleDialogs(model, onAction)
                }
            }
        }
    }
}

@Composable
private fun ModuleHome(model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? String
        val toId = to.key as? String
        if (fromId != null && toId != null && model.rows.any { it.id == fromId } && model.rows.any { it.id == toId }) {
            onAction(ModuleSettingsAction.Drag(fromId, toId))
        }
    }
    val moveUpLabel = stringResource(R.string.modules_move_up)
    val moveDownLabel = stringResource(R.string.modules_move_down)
    LazyColumn(state = listState, modifier = Modifier.widthIn(max = 840.dp).fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "master", contentType = "control") {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.modules_enabled), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.modules_enabled_description), style = MaterialTheme.typography.bodyMedium)
                    }
                    val label = stringResource(R.string.modules_enabled)
                    Switch(model.enabled, remember(onAction) { { onAction(ModuleSettingsAction.SetEnabled(it)) } }, enabled = model.busy == null,
                        modifier = remember(label) { Modifier.semantics { contentDescription = label } })
                }
            }
        }
        item(key = "tabs", contentType = "tabs") {
            PrimaryTabRow(selectedTabIndex = model.tab) {
                Tab(model.tab == 0, remember(onAction) { { onAction(ModuleSettingsAction.Tab(0)) } }, text = { Text(stringResource(R.string.modules_installed)) })
                Tab(model.tab == 1, remember(onAction) { { onAction(ModuleSettingsAction.Tab(1)) } }, text = { Text(stringResource(R.string.modules_catalogs)) })
            }
        }
        item(key = "message", contentType = "message") { ModuleMessage(model, onAction) }
        if (model.tab == 0) {
            item(key = "summary", contentType = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.modules_counts, model.rows.size, model.enabledCount), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.modules_order_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!model.enabled) Text(stringResource(R.string.modules_off), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (model.rows.isEmpty()) item(key = "empty", contentType = "empty") {
                ModuleEmpty(R.string.modules_empty, R.string.modules_empty_description, R.string.modules_import_file, onAction, ModuleSettingsAction.File)
            }
            items(model.rows, key = { it.id }, contentType = { "module" }) { row ->
                ReorderableItem(reorder, key = row.id) { dragging ->
                    val moveUp = remember(row.id, onAction) { { onAction(ModuleSettingsAction.Move(row.id, -1)); true } }
                    val moveDown = remember(row.id, onAction) { { onAction(ModuleSettingsAction.Move(row.id, 1)); true } }
                    val accessibility = remember(row.position, model.rows.size, model.busy, moveUpLabel, moveDownLabel, moveUp, moveDown) {
                        Modifier.semantics {
                            customActions = buildList {
                                if (model.busy == null && row.position > 1) add(CustomAccessibilityAction(moveUpLabel, moveUp))
                                if (model.busy == null && row.position < model.rows.size) add(CustomAccessibilityAction(moveDownLabel, moveDown))
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth().then(accessibility).shadow(if (dragging) 4.dp else 0.dp, MaterialTheme.shapes.medium)) {
                        ModuleRow(row, model, onAction) {
                            Box(Modifier.size(48.dp).draggableHandle(enabled = model.busy == null,
                                onDragStopped = { onAction(ModuleSettingsAction.CommitOrder) }), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.drag_handle), stringResource(R.string.modules_move, row.name))
                            }
                        }
                    }
                }
            }
        } else {
            if (model.catalogs.isEmpty()) item(key = "catalogs_empty", contentType = "empty") {
                ModuleEmpty(R.string.modules_catalogs_empty, R.string.modules_catalogs_empty_description, R.string.modules_add_catalog, onAction, ModuleSettingsAction.Url(true))
            }
            items(model.catalogs, key = { it.id }, contentType = { "catalog" }) { row -> ModuleCatalogRow(row, model, onAction) }
        }
    }
}

@Composable
private fun ModuleRow(row: ModuleRowUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit, handle: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        handle()
        Column(Modifier.weight(1f).clickable(enabled = model.busy == null,
            onClick = remember(row.id, onAction) { { onAction(ModuleSettingsAction.OpenModule(row.id)) } }).padding(vertical = 14.dp, horizontal = 4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.subtitle.isNotBlank()) Text(row.subtitle, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.modules_position, row.position, model.rows.size), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(row.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(row.enabled, remember(row.id, onAction) { { onAction(ModuleSettingsAction.Enable(row.id, it)) } },
            enabled = model.busy == null, modifier = remember(row.name) { Modifier.semantics { contentDescription = row.name } })
        ModuleMenu(model.menu == row.id, model.busy == null,
            remember(row.id, onAction) { { onAction(ModuleSettingsAction.Menu(row.id)) } },
            remember(onAction) { { onAction(ModuleSettingsAction.Menu(null)) } }) {
            ModuleMenuItem(R.string.modules_move_up, row.position > 1, onAction, ModuleSettingsAction.Move(row.id, -1))
            ModuleMenuItem(R.string.modules_move_down, row.position < model.rows.size, onAction, ModuleSettingsAction.Move(row.id, 1))
            ModuleMenuItem(R.string.modules_check, true, onAction, ModuleSettingsAction.Check(row.id))
            ModuleMenuItem(R.string.modules_check_update, row.canUpdate, onAction, ModuleSettingsAction.Update(row.id))
            ModuleMenuItem(R.string.modules_remove, true, onAction, ModuleSettingsAction.Remove(row.id, false))
        }
    }
}

@Composable
private fun ModuleCatalogRow(row: ModuleCatalogRowUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = model.busy == null,
                onClick = remember(row.id, onAction) { { onAction(ModuleSettingsAction.OpenCatalog(row.id)) } }).padding(4.dp)) {
                Text(row.name.ifBlank { stringResource(R.string.modules_file_origin) }, style = MaterialTheme.typography.titleMedium)
                Text(row.origin, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.modules_catalog_count, row.count), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.modules_last_refresh, row.refreshed), style = MaterialTheme.typography.bodySmall)
            }
            ModuleMenu(model.menu == row.id, model.busy == null,
                remember(row.id, onAction) { { onAction(ModuleSettingsAction.Menu(row.id)) } },
                remember(onAction) { { onAction(ModuleSettingsAction.Menu(null)) } }) {
                ModuleMenuItem(R.string.modules_refresh, row.origin.isNotBlank(), onAction, ModuleSettingsAction.Refresh(row.id))
                ModuleMenuItem(R.string.modules_edit_catalog, true, onAction, ModuleSettingsAction.Url(true, row.id))
                ModuleMenuItem(R.string.modules_remove, true, onAction, ModuleSettingsAction.Remove(row.id, true))
            }
        }
    }
}

@Composable
private fun ModuleDetails(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxHeight().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "message", contentType = "message") { ModuleMessage(model, onAction) }
        item(key = "identity", contentType = "identity") {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(detail.row.subtitle, style = MaterialTheme.typography.titleSmall)
                    if (detail.description.isNotBlank()) Text(detail.description)
                    Text(detail.origin.ifBlank { stringResource(R.string.modules_file_origin) }, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (detail.row.enabled) R.string.modules_provider_enabled else R.string.modules_provider_disabled), Modifier.weight(1f))
                        Switch(detail.row.enabled, remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Enable(detail.row.id, it)) } }, enabled = model.busy == null,
                            modifier = remember(detail.row.name) { Modifier.semantics { contentDescription = detail.row.name } })
                    }
                    Text(stringResource(R.string.modules_position, detail.row.position, model.rows.size))
                    Row {
                        TextButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Move(detail.row.id, -1)) } }, enabled = model.busy == null && detail.row.position > 1) { Text(stringResource(R.string.modules_move_up)) }
                        TextButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Move(detail.row.id, 1)) } }, enabled = model.busy == null && detail.row.position < model.rows.size) { Text(stringResource(R.string.modules_move_down)) }
                    }
                }
            }
        }
        item(key = "capabilities", contentType = "section") {
            ModuleSection(R.string.modules_capabilities) { detail.capabilities.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) } }
        }
        item(key = "configuration", contentType = "section") {
            Text(stringResource(R.string.modules_configuration), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (detail.qualityDeclared) R.string.modules_quality_description else R.string.modules_quality_default), style = MaterialTheme.typography.bodyMedium)
        }
        items(detail.fields, key = { it.key }, contentType = { it.type.name }) { field -> ModuleField(field, model, onAction) }
        if (detail.fields.isNotEmpty()) item(key = "save", contentType = "action") {
            Button(onClick = remember(onAction) { { onAction(ModuleSettingsAction.Save) } }, enabled = detail.dirty && detail.fields.none { it.error != null } && model.busy == null) {
                Text(stringResource(R.string.save))
            }
        }
        item(key = "diagnostics", contentType = "section") {
            ModuleSection(R.string.modules_diagnostics) {
                Text(stringResource(detail.row.status), style = MaterialTheme.typography.titleSmall)
                if (detail.row.status == R.string.modules_ready) Text(stringResource(R.string.modules_ready_description), style = MaterialTheme.typography.bodySmall)
                detail.diagnosticTime?.let { Text(stringResource(R.string.modules_diagnostic_time, it, detail.elapsedMs), style = MaterialTheme.typography.bodySmall) }
                detail.failure?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Check(detail.row.id)) } }, enabled = model.busy == null) { Text(stringResource(R.string.modules_check)) }
                TextButton(onClick = remember(onAction) { { onAction(ModuleSettingsAction.CopyDiagnostics) } }) { Text(stringResource(R.string.modules_diagnostics_copy)) }
            }
        }
        item(key = "updates", contentType = "section") {
            ModuleSection(R.string.modules_updates) {
                if (!detail.row.canUpdate) Text(stringResource(R.string.modules_updates_file), style = MaterialTheme.typography.bodyMedium)
                else TextButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Update(detail.row.id)) } }, enabled = model.busy == null) { Text(stringResource(R.string.modules_check_update)) }
                TextButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Remove(detail.row.id, false)) } }, enabled = model.busy == null) { Text(stringResource(R.string.modules_remove), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ModuleField(field: ModuleFieldUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    val change = remember(field.key, onAction) { { value: String -> onAction(ModuleSettingsAction.Field(field.key, value)) } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (field.type) {
            ModuleSettingType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(field.checked, remember(change) { { change(it.toString()) } }, enabled = model.busy == null,
                    modifier = remember(field.label) { Modifier.semantics { contentDescription = field.label } })
            }
            ModuleSettingType.SELECT -> Box {
                OutlinedButton(onClick = remember(field.key, onAction) { { onAction(ModuleSettingsAction.Menu("field:${field.key}")) } }, enabled = model.busy == null) {
                    Text(stringResource(R.string.modules_setting_value, field.label, field.options.firstOrNull { it.json == field.json }?.label.orEmpty()))
                }
                DropdownMenu(model.menu == "field:${field.key}", remember(onAction) { { onAction(ModuleSettingsAction.Menu(null)) } }) {
                    field.options.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = remember(option.json, change, onAction) { { change(option.json); onAction(ModuleSettingsAction.Menu(null)) } })
                    }
                }
            }
            ModuleSettingType.UNSUPPORTED -> {
                Text(field.label, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.modules_setting_unsupported), color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val password = field.sensitive
                val transformation = remember(password, field.revealed) { if (password && !field.revealed) PasswordVisualTransformation() else VisualTransformation.None }
                val keyboard = remember(field.type) { KeyboardOptions(keyboardType = when (field.type) {
                    ModuleSettingType.NUMBER -> KeyboardType.Decimal
                    ModuleSettingType.PASSWORD -> KeyboardType.Password
                    else -> KeyboardType.Text
                }) }
                OutlinedTextField(field.text, change, modifier = Modifier.fillMaxWidth(), label = { Text(field.label) }, enabled = model.busy == null,
                    singleLine = true, isError = field.error != null, visualTransformation = transformation, keyboardOptions = keyboard,
                    trailingIcon = if (password) { {
                        IconButton(onClick = remember(field.key, onAction) { { onAction(ModuleSettingsAction.Reveal(field.key)) } }) {
                            Icon(if (field.revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (field.revealed) R.string.modules_hide_password else R.string.modules_show_password))
                        }
                    } } else null)
            }
        }
        if (field.description.isNotBlank()) Text(field.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        field.error?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ModuleCatalogDetails(detail: ModuleCatalogDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxHeight(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "message", contentType = "message") { ModuleMessage(model, onAction) }
        item(key = "summary", contentType = "summary") {
            Text(detail.row.origin, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.modules_last_refresh, detail.row.refreshed))
            TextButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Refresh(detail.row.id)) } }, enabled = model.busy == null && detail.row.origin.isNotBlank()) { Text(stringResource(R.string.modules_refresh)) }
        }
        items(detail.entries, key = { it.key }, contentType = { "entry" }) { entry ->
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodySmall)
                    if (entry.description.isNotBlank()) Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                    if (entry.installed) Text(stringResource(R.string.modules_already_installed), style = MaterialTheme.typography.labelMedium)
                    if (entry.capabilities.isEmpty()) Text(stringResource(R.string.modules_capabilities_pending), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(stringResource(R.string.modules_capabilities), style = MaterialTheme.typography.labelMedium)
                        entry.capabilities.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (entry.supported) Text(stringResource(R.string.modules_compatibility_pending), style = MaterialTheme.typography.bodySmall)
                    if (!entry.supported) Text(stringResource(R.string.modules_unsupported_description), color = MaterialTheme.colorScheme.error)
                    else Button(onClick = remember(entry.download, onAction) { { entry.download?.let { onAction(ModuleSettingsAction.Install(it)) } } }, enabled = model.busy == null) {
                        Text(stringResource(if (entry.installed) R.string.modules_check_update else R.string.modules_install))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleDialogs(model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    val dismiss = remember(onAction) { { onAction(ModuleSettingsAction.Dismiss) } }
    val confirm = remember(onAction) { { onAction(ModuleSettingsAction.Confirm) } }
    when (model.dialog) {
        ModuleSettingsDialog.ADD -> ModalBottomSheet(onDismissRequest = dismiss) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = remember(onAction) { { onAction(ModuleSettingsAction.File) } }) { Text(stringResource(R.string.modules_import_file)) }
                TextButton(onClick = remember(onAction) { { onAction(ModuleSettingsAction.Url(false)) } }) { Text(stringResource(R.string.modules_add_url)) }
                TextButton(onClick = remember(onAction) { { onAction(ModuleSettingsAction.Url(true)) } }) { Text(stringResource(R.string.modules_add_catalog)) }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
        ModuleSettingsDialog.MODULE_URL, ModuleSettingsDialog.CATALOG_URL -> Dialog(onDismissRequest = dismiss, properties = remember { DialogProperties(usePlatformDefaultWidth = false) }) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(stringResource(if (model.dialog == ModuleSettingsDialog.CATALOG_URL) R.string.modules_add_catalog else R.string.modules_add_url)) },
                    navigationIcon = { IconButton(onClick = dismiss, enabled = model.busy == null) { Icon(painterResource(R.drawable.close), stringResource(android.R.string.cancel)) } })
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxHeight().imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "url", contentType = "field") {
                        OutlinedTextField(model.url, remember(onAction) { { onAction(ModuleSettingsAction.UrlChanged(it)) } }, label = { Text(stringResource(R.string.modules_url)) },
                            supportingText = { Text(stringResource(R.string.modules_url_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = remember { KeyboardOptions(keyboardType = KeyboardType.Uri) }, enabled = model.busy == null, isError = model.messageIsError)
                    }
                    item(key = "message", contentType = "message") { ModuleMessage(model, onAction) }
                    item(key = "submit", contentType = "action") {
                        Button(onClick = remember(onAction) { { onAction(ModuleSettingsAction.SubmitUrl) } }, enabled = model.url.isNotBlank() && model.busy == null) { Text(stringResource(R.string.modules_continue)) }
                    }
                }
                }
            }
        }
        ModuleSettingsDialog.REVIEW -> model.review?.let { review ->
            AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (review.catalogCount != null) R.string.modules_catalog_review else R.string.modules_install_review)) },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item(key = "identity", contentType = "identity") {
                            Text(review.name.ifBlank { stringResource(R.string.modules_file_origin) }, style = MaterialTheme.typography.titleMedium)
                            if (review.currentVersion != null) Text(stringResource(R.string.modules_update_versions, review.currentVersion, review.version)) else if (review.version.isNotBlank()) Text(review.version)
                            Text(review.origin.ifBlank { stringResource(R.string.modules_file_origin) }, style = MaterialTheme.typography.bodySmall)
                            if (review.description.isNotBlank()) Text(review.description)
                            if (review.catalogCount != null) Text(stringResource(R.string.modules_catalog_count, review.catalogCount))
                            else Text(stringResource(R.string.modules_install_description))
                            if (review.schemaChanged) Text(stringResource(R.string.modules_configuration_changed))
                            if (review.requiredSettings.isNotEmpty()) Text(stringResource(R.string.modules_required_settings, review.requiredSettings.joinToString()))
                        }
                        items(review.capabilities, key = { it }, contentType = { "capability" }) { Text(stringResource(it)) }
                        item(key = "message", contentType = "message") { ModuleMessage(model, onAction) }
                    }
                }, confirmButton = { TextButton(onClick = confirm, enabled = model.busy == null) { Text(stringResource(if (review.currentVersion != null) R.string.modules_replace else R.string.modules_install)) } },
                dismissButton = { TextButton(onClick = dismiss, enabled = model.busy == null) { Text(stringResource(android.R.string.cancel)) } })
        }
        ModuleSettingsDialog.REMOVE_MODULE, ModuleSettingsDialog.REMOVE_CATALOG, ModuleSettingsDialog.DISCARD -> {
            val title = when (model.dialog) {
                ModuleSettingsDialog.REMOVE_MODULE -> R.string.modules_remove_module_title
                ModuleSettingsDialog.REMOVE_CATALOG -> R.string.modules_remove_catalog_title
                else -> R.string.modules_discard_title
            }
            val description = when (model.dialog) {
                ModuleSettingsDialog.REMOVE_MODULE -> R.string.modules_remove_module_description
                ModuleSettingsDialog.REMOVE_CATALOG -> R.string.modules_remove_catalog_description
                else -> R.string.modules_discard_description
            }
            AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(title)) }, text = { Text(stringResource(description)) },
                confirmButton = { TextButton(onClick = confirm, enabled = model.busy == null) { Text(stringResource(if (model.dialog == ModuleSettingsDialog.DISCARD) R.string.modules_discard else R.string.modules_remove)) } },
                dismissButton = { TextButton(onClick = dismiss, enabled = model.busy == null) { Text(stringResource(android.R.string.cancel)) } })
        }
        null -> Unit
    }
}

@Composable
private fun ModuleMessage(model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    if (model.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
    model.message?.let { message ->
        Surface(color = if (model.messageIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(message), Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = remember(onAction) { { onAction(ModuleSettingsAction.ClearMessage) } }) { Icon(painterResource(R.drawable.close), stringResource(R.string.modules_dismiss)) }
            }
        }
    }
}

@Composable
private fun ModuleEmpty(title: Int, description: Int, actionLabel: Int, onAction: (ModuleSettingsAction) -> Unit, action: ModuleSettingsAction) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(description), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = remember(action, onAction) { { onAction(action) } }) { Text(stringResource(actionLabel)) }
    }
}

@Composable
private fun ModuleSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        content()
    } }
}

@Composable
private fun ModuleMenu(expanded: Boolean, enabled: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box {
        IconButton(onClick = onOpen, enabled = enabled) { Icon(painterResource(R.drawable.more_vert), stringResource(R.string.more)) }
        DropdownMenu(expanded, onDismiss, content = content)
    }
}

@Composable
private fun ModuleMenuItem(label: Int, enabled: Boolean, onAction: (ModuleSettingsAction) -> Unit, action: ModuleSettingsAction) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = enabled,
        onClick = remember(action, onAction) { { onAction(ModuleSettingsAction.Menu(null)); onAction(action) } })
}
