package dev.mobileide.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.MobileIdeApplication
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.core.designsystem.component.MobileIdeLogo
import dev.mobileide.core.designsystem.component.ProjectListItem
import dev.mobileide.core.designsystem.component.SectionHeader
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.designsystem.theme.MobileIdeTheme
import dev.mobileide.core.ui.ProjectAccessErrorDialog
import dev.mobileide.core.ui.ProjectRelinkDialog
import dev.mobileide.core.ui.RemoveProjectDialog
import dev.mobileide.core.ui.StoragePermissionBanner
import dev.mobileide.core.ui.rememberStoragePermissionState
import dev.mobileide.core.util.copyToClipboard
import dev.mobileide.domain.model.Project
import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.repository.OpenProjectResult
import dev.mobileide.domain.repository.ProjectFailure
import dev.mobileide.domain.repository.RelinkProjectResult
import dev.mobileide.feature.files.FolderPickerDialog
import dev.mobileide.feature.home.components.QuickActions
import dev.mobileide.feature.home.components.WelcomeSection
import dev.mobileide.feature.home.model.HomeUiState
import kotlinx.coroutines.launch
import java.io.File

private sealed interface PickerPurpose {
    data object OpenProject : PickerPurpose
    data class Relink(val project: Project) : PickerPurpose
}

@Composable
fun HomeScreen(
    onNewProject: () -> Unit,
    onOpenWorkspace: (Long) -> Unit,
    onViewAllProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember(context) {
        (context.applicationContext as MobileIdeApplication).container
    }
    val permission = rememberStoragePermissionState(container.allFilesAccess)

    var removeTarget by remember { mutableStateOf<Project?>(null) }
    var accessTarget by remember { mutableStateOf<Project?>(null) }
    var relinkTarget by remember { mutableStateOf<Project?>(null) }
    var pickerPurpose by remember { mutableStateOf<PickerPurpose?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(permission.granted) {
        if (permission.granted) viewModel.ensureProjectRoot()
    }

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

    fun startPicker(purpose: PickerPurpose) {
        if (!permission.granted) {
            showPermissionDialog = true
            return
        }
        pickerPurpose = purpose
    }

    HomeContent(
        uiState = uiState,
        storageGranted = permission.granted,
        onGrantStorage = permission.request,
        onNewProject = onNewProject,
        onOpenProject = { startPicker(PickerPurpose.OpenProject) },
        onViewAllProjects = onViewAllProjects,
        onOpenSettings = onOpenSettings,
        onProjectClick = { project -> openProject(project) },
        onCopyPath = { project ->
            project.path?.let { path ->
                context.copyToClipboard(project.name, path)
                onShowMessage(context.getString(R.string.message_location_copied))
            }
        },
        onRemoveProject = { removeTarget = it },
        onRelinkProject = { relinkTarget = it },
        modifier = modifier
    )

    pickerPurpose?.let { purpose ->
        val rootPath = viewModel.projectRootPath()
        val startPath = remember(rootPath) {
            if (File(rootPath).isDirectory) rootPath else viewModel.externalStorageRoot()
        }
        FolderPickerDialog(
            browser = container.storageBrowser,
            startPath = startPath,
            title = stringResource(
                if (purpose is PickerPurpose.Relink) {
                    R.string.relink_picker_title
                } else {
                    R.string.folder_picker_open_title
                }
            ),
            onDismiss = { pickerPurpose = null },
            onSelect = { path ->
                pickerPurpose = null
                scope.launch {
                    when (purpose) {
                        PickerPurpose.OpenProject -> when (val result = viewModel.openProject(path)) {
                            is OpenProjectResult.Success -> onOpenWorkspace(result.projectId)
                            is OpenProjectResult.Failure -> {
                                onShowMessage(context.getString(openErrorMessage(result.reason)))
                            }
                        }

                        is PickerPurpose.Relink -> {
                            when (val result = viewModel.relinkProject(purpose.project.id, path)) {
                                RelinkProjectResult.Success -> {
                                    onShowMessage(
                                        context.getString(
                                            R.string.message_project_relocated,
                                            purpose.project.name
                                        )
                                    )
                                }

                                RelinkProjectResult.AlreadyRegistered -> {
                                    onShowMessage(context.getString(R.string.relink_error_registered))
                                }

                                RelinkProjectResult.NotFound -> {
                                    onShowMessage(context.getString(R.string.remove_error))
                                }

                                is RelinkProjectResult.Failure -> {
                                    onShowMessage(context.getString(R.string.relink_error))
                                }
                            }
                        }
                    }
                }
            }
        )
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

    removeTarget?.let { project ->
        RemoveProjectDialog(
            project = project,
            allowFileDeletion = false,
            onDismiss = { removeTarget = null },
            onRemoveOnly = {
                removeTarget = null
                viewModel.removeProject(project.id)
                onShowMessage(context.getString(R.string.message_project_removed, project.name))
            },
            onDeleteFiles = {}
        )
    }

    relinkTarget?.let { project ->
        ProjectRelinkDialog(
            project = project,
            onDismiss = { relinkTarget = null },
            onLocate = {
                relinkTarget = null
                startPicker(PickerPurpose.Relink(project))
            },
            onRemove = {
                relinkTarget = null
                viewModel.removeProject(project.id)
                onShowMessage(context.getString(R.string.message_project_removed, project.name))
            }
        )
    }

    accessTarget?.let { project ->
        ProjectAccessErrorDialog(
            project = project,
            onDismiss = { accessTarget = null },
            onRemove = {
                accessTarget = null
                viewModel.removeProject(project.id)
                onShowMessage(context.getString(R.string.message_project_removed, project.name))
            }
        )
    }
}

private fun openErrorMessage(reason: ProjectFailure): Int = when (reason) {
    ProjectFailure.PERMISSION_DENIED -> R.string.open_error_permission
    ProjectFailure.INVALID_PATH -> R.string.open_error_invalid_path
    else -> R.string.open_error_io
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    storageGranted: Boolean,
    onGrantStorage: () -> Unit,
    onNewProject: () -> Unit,
    onOpenProject: () -> Unit,
    onViewAllProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    onProjectClick: (Project) -> Unit,
    onCopyPath: (Project) -> Unit,
    onRemoveProject: (Project) -> Unit,
    onRelinkProject: (Project) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeTopBar(onOpenSettings = onOpenSettings)

        if (!storageGranted) {
            StoragePermissionBanner(onGrant = onGrantStorage)
        }

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
                    top = MobileIdeSpacing.sm,
                    bottom = MobileIdeSpacing.xxxl
                )
            ) {
                item(key = "welcome") {
                    Spacer(Modifier.height(MobileIdeSpacing.md))
                    WelcomeSection()
                    Spacer(Modifier.height(MobileIdeSpacing.xxl))
                    QuickActions(
                        onNewProject = onNewProject,
                        onOpenProject = onOpenProject
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.xxxl))
                    SectionHeader(
                        title = stringResource(R.string.home_recent_title),
                        actionLabel = stringResource(R.string.action_view_all),
                        onAction = onViewAllProjects
                    )
                }

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
                            text = stringResource(R.string.home_load_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = MobileIdeSpacing.xxl)
                        )
                    }

                    uiState.recentProjects.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            iconRes = R.drawable.ic_folder,
                            title = stringResource(R.string.home_recent_empty_title),
                            description = stringResource(R.string.home_recent_empty_description)
                        )
                    }

                    else -> itemsIndexed(
                        items = uiState.recentProjects,
                        key = { _, project -> project.id }
                    ) { index, project ->
                        ProjectListItem(
                            project = project,
                            onClick = { onProjectClick(project) },
                            onOpen = { onProjectClick(project) },
                            onCopyPath = if (project.needsRelink) {
                                null
                            } else {
                                { onCopyPath(project) }
                            },
                            onRemove = { onRemoveProject(project) },
                            onRelink = if (project.needsRelink) {
                                { onRelinkProject(project) }
                            } else {
                                null
                            }
                        )
                        if (index < uiState.recentProjects.lastIndex) {
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
}

@Composable
private fun HomeTopBar(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(
                    start = MobileIdeDimens.screenHorizontalPadding,
                    end = MobileIdeSpacing.sm,
                    top = MobileIdeSpacing.sm,
                    bottom = MobileIdeSpacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MobileIdeLogo(size = MobileIdeDimens.topBarIconSize)
            Spacer(Modifier.width(MobileIdeSpacing.md))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_tune),
                    contentDescription = stringResource(R.string.nav_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 820)
@Composable
private fun HomeContentPreview() {
    MobileIdeTheme(darkTheme = false) {
        HomeContent(
            uiState = HomeUiState(
                isLoading = false,
                recentProjects = listOf(
                    Project(
                        id = 1,
                        name = "notes-app",
                        type = ProjectType.KOTLIN,
                        path = "/storage/emulated/0/AndroidProjects/notes-app",
                        locationName = "AndroidProjects",
                        createdAt = System.currentTimeMillis(),
                        lastOpenedAt = System.currentTimeMillis() - 12 * 60_000L,
                        updatedAt = System.currentTimeMillis()
                    ),
                    Project(
                        id = 2,
                        name = "legacy-web",
                        type = ProjectType.WEB,
                        path = null,
                        locationName = "",
                        createdAt = System.currentTimeMillis(),
                        lastOpenedAt = System.currentTimeMillis() - 5 * 3_600_000L,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            ),
            storageGranted = true,
            onGrantStorage = {},
            onNewProject = {},
            onOpenProject = {},
            onViewAllProjects = {},
            onOpenSettings = {},
            onProjectClick = {},
            onCopyPath = {},
            onRemoveProject = {},
            onRelinkProject = {}
        )
    }
}
