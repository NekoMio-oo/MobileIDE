package dev.mobileide.feature.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.MobileIdeApplication
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.core.designsystem.component.ProjectListItem
import dev.mobileide.core.designsystem.component.ScreenHeader
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.ui.DeleteProjectFilesDialog
import dev.mobileide.core.ui.ProjectAccessErrorDialog
import dev.mobileide.core.ui.ProjectRelinkDialog
import dev.mobileide.core.ui.RemoveProjectDialog
import dev.mobileide.core.ui.RenameProjectDialog
import dev.mobileide.core.ui.StoragePermissionBanner
import dev.mobileide.core.ui.projectNameErrorMessage
import dev.mobileide.core.ui.rememberStoragePermissionState
import dev.mobileide.core.util.copyToClipboard
import dev.mobileide.domain.model.Project
import dev.mobileide.domain.repository.DeleteProjectResult
import dev.mobileide.domain.repository.RelinkProjectResult
import dev.mobileide.domain.repository.RenameProjectResult
import dev.mobileide.feature.files.FolderPickerDialog
import kotlinx.coroutines.launch

@Composable
fun ProjectsScreen(
    onShowMessage: (String) -> Unit,
    onOpenWorkspace: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember(context) {
        (context.applicationContext as MobileIdeApplication).container
    }
    val permission = rememberStoragePermissionState(container.allFilesAccess)

    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var removeTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var accessTarget by remember { mutableStateOf<Project?>(null) }
    var relinkTarget by remember { mutableStateOf<Project?>(null) }
    var pickerTarget by remember { mutableStateOf<Project?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }

    fun openProject(project: Project) {
        if (project.needsRelink) {
            relinkTarget = project
            return
        }
        if (!permission.granted) {
            showPermissionDialog = true
            return
        }
        scope.launch {
            if (viewModel.checkAccess(project.id)) {
                onOpenWorkspace(project.id)
            } else {
                accessTarget = project
            }
        }
    }

    fun startRelinkPicker(project: Project) {
        if (!permission.granted) {
            showPermissionDialog = true
            return
        }
        pickerTarget = project
    }

    fun renameProject(project: Project, newName: String) {
        if (isWorking) return
        isWorking = true
        scope.launch {
            when (val result = viewModel.rename(project.id, newName)) {
                RenameProjectResult.Success -> {
                    renameTarget = null
                    onShowMessage(context.getString(R.string.message_project_renamed, newName))
                }

                is RenameProjectResult.InvalidName ->
                    onShowMessage(context.getString(projectNameErrorMessage(result.error)))

                RenameProjectResult.NotFound ->
                    onShowMessage(context.getString(R.string.remove_error))

                is RenameProjectResult.Failure ->
                    onShowMessage(context.getString(R.string.rename_error))
            }
            isWorking = false
        }
    }

    fun removeProject(project: Project) {
        if (isWorking) return
        isWorking = true
        scope.launch {
            when (viewModel.remove(project.id)) {
                DeleteProjectResult.Success -> {
                    removeTarget = null
                    onShowMessage(context.getString(R.string.message_project_removed, project.name))
                }

                is DeleteProjectResult.Failure ->
                    onShowMessage(context.getString(R.string.remove_error))
            }
            isWorking = false
        }
    }

    fun deleteProjectFiles(project: Project) {
        if (isWorking) return
        isWorking = true
        scope.launch {
            when (viewModel.deleteFiles(project.id)) {
                DeleteProjectResult.Success -> {
                    deleteTarget = null
                    onShowMessage(context.getString(R.string.message_project_deleted, project.name))
                }

                is DeleteProjectResult.Failure ->
                    onShowMessage(context.getString(R.string.delete_files_error))
            }
            isWorking = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.projects_title),
            subtitle = pluralStringResource(
                R.plurals.projects_count,
                uiState.visibleProjects.size,
                uiState.visibleProjects.size
            )
        )

        if (!permission.granted) {
            StoragePermissionBanner(onGrant = permission.request)
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier
                    .widthIn(max = MobileIdeDimens.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = MobileIdeDimens.screenHorizontalPadding),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                placeholder = { Text(stringResource(R.string.project_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        Spacer(Modifier.height(MobileIdeSpacing.md))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = MobileIdeDimens.contentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentPadding = PaddingValues(
                    start = MobileIdeDimens.screenHorizontalPadding,
                    end = MobileIdeDimens.screenHorizontalPadding,
                    bottom = MobileIdeSpacing.xxxl
                )
            ) {
                when {
                    uiState.isLoading -> item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MobileIdeSpacing.xxxl),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    uiState.loadFailed -> item(key = "error") {
                        Text(
                            text = stringResource(R.string.projects_load_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = MobileIdeSpacing.xxl)
                        )
                    }

                    uiState.visibleProjects.isEmpty() -> item(key = "empty") {
                        if (uiState.query.isBlank()) {
                            EmptyState(
                                iconRes = R.drawable.ic_folder,
                                title = stringResource(R.string.projects_empty_title),
                                description = stringResource(R.string.projects_empty_description)
                            )
                        } else {
                            EmptyState(
                                iconRes = R.drawable.ic_search,
                                title = stringResource(R.string.projects_search_empty_title),
                                description = stringResource(R.string.projects_search_empty_description)
                            )
                        }
                    }

                    else -> itemsIndexed(
                        items = uiState.visibleProjects,
                        key = { _, project -> project.id }
                    ) { index, project ->
                        ProjectListItem(
                            project = project,
                            onClick = { openProject(project) },
                            onOpen = { openProject(project) },
                            onCopyPath = if (project.needsRelink) {
                                null
                            } else {
                                {
                                    project.path?.let { path ->
                                        context.copyToClipboard(project.name, path)
                                        onShowMessage(context.getString(R.string.message_location_copied))
                                    }
                                }
                            },
                            onRemove = { removeTarget = project },
                            onRename = if (project.needsRelink) {
                                null
                            } else {
                                { renameTarget = project }
                            },
                            onDeleteFiles = if (project.needsRelink) {
                                null
                            } else {
                                { deleteTarget = project }
                            },
                            onRelink = if (project.needsRelink) {
                                { relinkTarget = project }
                            } else {
                                null
                            }
                        )
                        if (index < uiState.visibleProjects.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 60.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.storage_permission_title)) },
            text = { Text(stringResource(R.string.storage_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        permission.request()
                    }
                ) {
                    Text(stringResource(R.string.storage_permission_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pickerTarget?.let { project ->
        FolderPickerDialog(
            browser = container.storageBrowser,
            startPath = container.allFilesAccess.externalStorageRoot(),
            title = stringResource(R.string.relink_picker_title),
            onDismiss = { pickerTarget = null },
            onSelect = { path ->
                pickerTarget = null
                scope.launch {
                    when (val result = viewModel.relink(project.id, path)) {
                        RelinkProjectResult.Success ->
                            onShowMessage(
                                context.getString(R.string.message_project_relocated, project.name)
                            )

                        RelinkProjectResult.AlreadyRegistered ->
                            onShowMessage(context.getString(R.string.relink_error_registered))

                        RelinkProjectResult.NotFound ->
                            onShowMessage(context.getString(R.string.remove_error))

                        is RelinkProjectResult.Failure ->
                            onShowMessage(context.getString(R.string.relink_error))
                    }
                }
            }
        )
    }

    relinkTarget?.let { project ->
        ProjectRelinkDialog(
            project = project,
            onDismiss = { relinkTarget = null },
            onLocate = {
                relinkTarget = null
                startRelinkPicker(project)
            },
            onRemove = {
                relinkTarget = null
                removeProject(project)
            }
        )
    }

    renameTarget?.let { project ->
        RenameProjectDialog(
            project = project,
            isWorking = isWorking,
            onDismiss = { renameTarget = null },
            onConfirm = { newName -> renameProject(project, newName) }
        )
    }

    removeTarget?.let { project ->
        RemoveProjectDialog(
            project = project,
            allowFileDeletion = !project.needsRelink,
            onDismiss = { removeTarget = null },
            onRemoveOnly = { removeProject(project) },
            onDeleteFiles = {
                removeTarget = null
                deleteTarget = project
            }
        )
    }

    deleteTarget?.let { project ->
        DeleteProjectFilesDialog(
            project = project,
            isWorking = isWorking,
            onDismiss = { deleteTarget = null },
            onConfirm = { deleteProjectFiles(project) }
        )
    }

    accessTarget?.let { project ->
        ProjectAccessErrorDialog(
            project = project,
            onDismiss = { accessTarget = null },
            onRemove = {
                accessTarget = null
                removeProject(project)
            }
        )
    }
}
