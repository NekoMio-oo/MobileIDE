package dev.mobileide.feature.createproject

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.MobileIdeApplication
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.DetailTopBar
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.ui.StoragePermissionBanner
import dev.mobileide.core.ui.projectNameErrorMessage
import dev.mobileide.core.ui.rememberStoragePermissionState
import dev.mobileide.domain.model.ProjectType
import dev.mobileide.feature.createproject.model.CreateProjectError

@Composable
fun CreateProjectScreen(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateProjectViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as MobileIdeApplication).container
    }
    val permission = rememberStoragePermissionState(container.allFilesAccess)

    LaunchedEffect(permission.granted) {
        viewModel.onStoragePermissionChanged(permission.granted)
    }

    Column(modifier = modifier.fillMaxSize()) {
        DetailTopBar(
            title = stringResource(R.string.create_project_title),
            onBack = onBack
        )

        if (!permission.granted) {
            StoragePermissionBanner(onGrant = permission.request)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MobileIdeDimens.contentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(
                        horizontal = MobileIdeDimens.screenHorizontalPadding,
                        vertical = MobileIdeSpacing.md
                    )
            ) {
                Text(
                    text = stringResource(R.string.create_project_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(MobileIdeSpacing.xxl))

                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::onNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.create_project_name_label)) },
                    placeholder = { Text(stringResource(R.string.create_project_name_placeholder)) },
                    isError = uiState.nameError != null,
                    supportingText = {
                        uiState.nameError?.let {
                            Text(stringResource(projectNameErrorMessage(it)))
                        }
                    }
                )

                Spacer(Modifier.height(MobileIdeSpacing.xl))

                FieldLabel(stringResource(R.string.create_project_type_label))
                TypeSelector(
                    selected = uiState.type,
                    onSelected = viewModel::onTypeSelected
                )

                Spacer(Modifier.height(MobileIdeSpacing.xl))

                FieldLabel(stringResource(R.string.create_project_root_label))
                RootPathCard(
                    rootPath = uiState.rootPath,
                    isReady = uiState.isRootReady
                )

                uiState.error?.let { error ->
                    Spacer(Modifier.height(MobileIdeSpacing.md))
                    Text(
                        text = stringResource(createErrorMessage(error)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    uiState.errorDetail
                        ?.takeIf { it.isNotBlank() }
                        ?.let { detail ->
                            Text(
                                text = stringResource(R.string.create_project_error_detail, detail),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                }

                Spacer(Modifier.height(MobileIdeSpacing.xxl))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MobileIdeSpacing.md)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = viewModel::create,
                        enabled = !uiState.isCreating &&
                            permission.granted &&
                            uiState.isRootReady,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        if (uiState.isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.create_project_submit))
                        }
                    }
                }

                Spacer(Modifier.height(MobileIdeSpacing.xxl))
            }
        }
    }

    val createdProjectId = uiState.createdProjectId
    if (createdProjectId != null) {
        val createdPath = uiState.rootPath.trimEnd('/') + "/" + uiState.name.trim()
        AlertDialog(
            onDismissRequest = viewModel::consumeCreatedProject,
            title = { Text(stringResource(R.string.create_project_success_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.create_project_success_message,
                        uiState.name.trim(),
                        createdPath
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.consumeCreatedProject()
                        onCreated(createdProjectId)
                    }
                ) {
                    Text(stringResource(R.string.create_project_open))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.consumeCreatedProject()
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.create_project_done))
                }
            }
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = MobileIdeSpacing.sm)
    )
}

@Composable
private fun RootPathCard(rootPath: String, isReady: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(MobileIdeSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rootPath,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isReady) {
                        stringResource(R.string.create_root_ready)
                    } else {
                        stringResource(R.string.create_root_unavailable)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

@Composable
private fun TypeSelector(
    selected: ProjectType,
    onSelected: (ProjectType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        SelectorSurface(onClick = { expanded = true }) {
            Text(
                text = selected.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ProjectType.creatable.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        expanded = false
                        onSelected(type)
                    }
                )
            }
        }
    }
}

@Composable
private fun SelectorSurface(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MobileIdeSpacing.lg,
                vertical = MobileIdeSpacing.lg - 2.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@StringRes
private fun createErrorMessage(error: CreateProjectError): Int = when (error) {
    CreateProjectError.PERMISSION_DENIED -> R.string.create_error_permission
    CreateProjectError.ROOT_UNAVAILABLE -> R.string.create_error_root_unavailable
    CreateProjectError.DUPLICATE_NAME -> R.string.create_error_duplicate
    CreateProjectError.INVALID_PATH -> R.string.create_error_invalid_path
    CreateProjectError.IO -> R.string.create_error_io
}
