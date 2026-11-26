package dev.mobileide.feature.build

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.R
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.feature.build.model.BuildConfigUiState

/**
 * Build settings for one project. Only options that the installed toolchain
 * actually offers are shown (language, compiler, standard, target).
 */
@Composable
fun BuildConfigScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BuildConfigViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        ConfigTopBar(
            isSaving = uiState.isSaving,
            canSave = !uiState.isLoading && !uiState.isSaving,
            onBack = onBack,
            onSave = viewModel::save
        )
        if (uiState.isLoading || uiState.isProbing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        uiState.error?.let { error ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        if (uiState.saved) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.build_config_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    TextButton(onClick = viewModel::dismissMessage) {
                        Text(stringResource(R.string.runtimes_dismiss))
                    }
                }
            }
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (!uiState.language.hasBuildStep) {
                ConfigNotice(stringResource(R.string.build_config_unsupported_language))
            }

            SectionCard(title = stringResource(R.string.build_config_language)) {
                DropdownField(
                    label = stringResource(R.string.build_config_language),
                    value = uiState.language.name,
                    options = BuildLanguage.entries.map { it.name },
                    labelFor = { id -> BuildLanguage.fromId(id)?.displayName ?: id },
                    onSelect = { id -> BuildLanguage.fromId(id)?.let(viewModel::selectLanguage) }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.build_config_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(
                        label = stringResource(R.string.build_config_mode_debug),
                        selected = uiState.mode == BuildMode.DEBUG,
                        onClick = { viewModel.selectMode(BuildMode.DEBUG) }
                    )
                    ModeChip(
                        label = stringResource(R.string.build_config_mode_release),
                        selected = uiState.mode == BuildMode.RELEASE,
                        onClick = { viewModel.selectMode(BuildMode.RELEASE) }
                    )
                }
            }

            SectionCard(title = stringResource(R.string.build_config_compiler)) {
                if (uiState.compilers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.build_toolchain_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    DropdownField(
                        label = stringResource(R.string.build_config_compiler),
                        value = uiState.compiler,
                        options = uiState.compilers,
                        onSelect = viewModel::selectCompiler
                    )
                }
                if (uiState.standards.isNotEmpty() &&
                    uiState.language != BuildLanguage.TYPESCRIPT
                ) {
                    Spacer(Modifier.height(12.dp))
                    DropdownField(
                        label = stringResource(R.string.build_config_standard),
                        value = uiState.standard,
                        options = uiState.standards,
                        onSelect = viewModel::selectStandard
                    )
                }
            }

            SectionCard(title = stringResource(R.string.build_config_output)) {
                ConfigTextField(
                    label = stringResource(R.string.build_config_output),
                    value = uiState.outputName,
                    onValueChange = viewModel::setOutputName,
                    hint = "build/${uiState.mode.key}/${uiState.outputName}"
                )
                if (uiState.language == BuildLanguage.TYPESCRIPT) {
                    Spacer(Modifier.height(12.dp))
                    DropdownField(
                        label = stringResource(R.string.build_config_target),
                        value = uiState.target,
                        options = uiState.standards,
                        onSelect = viewModel::selectTarget
                    )
                }
                if (uiState.language == BuildLanguage.GO) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.build_config_target_fixed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = stringResource(R.string.build_config_sources)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.build_config_sources_auto),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.build_config_sources_selected,
                                if (uiState.sourcesAuto) {
                                    uiState.sources.size
                                } else {
                                    uiState.selectedSources.size
                                },
                                if (uiState.sourcesAuto) {
                                    uiState.sources.size
                                } else {
                                    uiState.selectedSources.size
                                },
                                uiState.sources.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.sourcesAuto,
                        onCheckedChange = viewModel::setSourcesAuto
                    )
                }

                if (!uiState.sourcesAuto) {
                    if (uiState.sources.isEmpty()) {
                        Text(
                            text = stringResource(R.string.build_config_no_sources),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            TextButton(onClick = viewModel::selectAllSources) {
                                Text(stringResource(R.string.build_config_select_all))
                            }
                            TextButton(onClick = viewModel::clearSources) {
                                Text(stringResource(R.string.build_config_select_none))
                            }
                        }
                        uiState.sources.take(150).forEach { source ->
                            SourceRow(
                                path = source,
                                checked = source in uiState.selectedSources,
                                onToggle = { viewModel.toggleSource(source) }
                            )
                        }
                    }
                }

                if (uiState.sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val noneLabel = stringResource(R.string.build_config_entry_none)
                    DropdownField(
                        label = stringResource(R.string.build_config_entry),
                        value = uiState.entryFile.ifBlank { noneLabel },
                        options = listOf("") + uiState.sources,
                        labelFor = { if (it.isBlank()) noneLabel else it },
                        onSelect = viewModel::setEntryFile
                    )
                }
            }

            SectionCard(title = stringResource(R.string.build_config_includes)) {
                ConfigTextField(
                    label = stringResource(R.string.build_config_includes),
                    value = uiState.includeDirs,
                    onValueChange = viewModel::setIncludeDirs,
                    singleLine = false,
                    hint = stringResource(R.string.build_config_list_hint)
                )
                Spacer(Modifier.height(12.dp))
                ConfigTextField(
                    label = stringResource(R.string.build_config_libdirs),
                    value = uiState.libraryDirs,
                    onValueChange = viewModel::setLibraryDirs,
                    singleLine = false,
                    hint = stringResource(R.string.build_config_list_hint)
                )
                Spacer(Modifier.height(12.dp))
                ConfigTextField(
                    label = stringResource(R.string.build_config_libs),
                    value = uiState.libraries,
                    onValueChange = viewModel::setLibraries,
                    singleLine = false,
                    hint = "m, pthread"
                )
            }

            SectionCard(title = stringResource(R.string.build_config_cflags)) {
                ConfigTextField(
                    label = stringResource(R.string.build_config_cflags),
                    value = uiState.compileFlags,
                    onValueChange = viewModel::setCompileFlags,
                    singleLine = false,
                    hint = stringResource(R.string.build_config_flags_hint)
                )
                Spacer(Modifier.height(12.dp))
                ConfigTextField(
                    label = stringResource(R.string.build_config_lflags),
                    value = uiState.linkFlags,
                    onValueChange = viewModel::setLinkFlags,
                    singleLine = false,
                    hint = stringResource(R.string.build_config_flags_hint)
                )
                if (uiState.supportsClasspath) {
                    Spacer(Modifier.height(12.dp))
                    ConfigTextField(
                        label = stringResource(R.string.build_config_classpath),
                        value = uiState.classpath,
                        onValueChange = viewModel::setClasspath,
                        singleLine = false,
                        hint = stringResource(R.string.build_config_list_hint)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConfigTopBar(
    isSaving: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(R.string.build_config_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )
        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        } else {
            TextButton(onClick = onSave, enabled = canSave) {
                Text(stringResource(R.string.build_config_save))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            content()
        }
    }
}

@Composable
private fun ConfigNotice(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun SourceRow(path: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    labelFor: (String) -> String = { it },
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = options.isNotEmpty()
            ) {
                Text(
                    text = labelFor(value).ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = labelFor(option),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    hint: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        supportingText = hint?.let { text ->
            { Text(text, style = MaterialTheme.typography.labelSmall) }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth()
    )
}
