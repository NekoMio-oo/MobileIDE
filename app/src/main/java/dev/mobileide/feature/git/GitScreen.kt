package dev.mobileide.feature.git

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.core.designsystem.component.ScreenHeader
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.ui.StoragePermissionBanner
import dev.mobileide.core.ui.rememberStoragePermissionState
import dev.mobileide.feature.git.model.GitProjectItem

/**
 * Top-level Git tab. A Git panel always targets one project, so the tab first
 * asks which project to manage; the project screen itself lives at
 * `git/{projectId}` and is reachable from the file browser and editor later.
 */
@Composable
fun GitScreen(
    onOpenProjectGit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GitLandingViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as MobileIdeApplication).container
    }
    val permission = rememberStoragePermissionState(container.allFilesAccess)

    // Re-check the `.git` marker when the tab is shown again after init/clone.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.git_title),
            subtitle = stringResource(R.string.git_landing_subtitle)
        )

        if (!permission.granted) {
            StoragePermissionBanner(onGrant = permission.request)
        }

        when {
            uiState.isLoading -> LoadingBox()

            uiState.loadFailed -> EmptyState(
                iconRes = R.drawable.ic_git,
                title = stringResource(R.string.git_landing_load_error_title),
                description = stringResource(R.string.git_landing_load_error_description),
                modifier = Modifier.padding(horizontal = MobileIdeDimens.screenHorizontalPadding)
            )

            uiState.projects.isEmpty() -> EmptyState(
                iconRes = R.drawable.ic_git,
                title = stringResource(R.string.git_landing_empty_title),
                description = stringResource(R.string.git_landing_empty_description),
                modifier = Modifier.padding(horizontal = MobileIdeDimens.screenHorizontalPadding)
            )

            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = MobileIdeDimens.contentMaxWidth)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = MobileIdeDimens.screenHorizontalPadding,
                        end = MobileIdeDimens.screenHorizontalPadding,
                        top = MobileIdeSpacing.xs,
                        bottom = MobileIdeSpacing.xxxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.sm)
                ) {
                    items(items = uiState.projects, key = { it.project.id }) { item ->
                        GitProjectRow(
                            item = item,
                            onClick = { onOpenProjectGit(item.project.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun GitProjectRow(
    item: GitProjectItem,
    onClick: () -> Unit
) {
    val project = item.project
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(
                start = MobileIdeSpacing.md,
                end = MobileIdeSpacing.xs,
                top = MobileIdeSpacing.md,
                bottom = MobileIdeSpacing.md
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(MobileIdeDimens.projectIconSize)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(MobileIdeSpacing.md)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(MobileIdeSpacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(project.type.label)
                        if (project.locationName.isNotBlank()) {
                            append(" · ")
                            append(project.locationName)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.hasRepository) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.git_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(MobileIdeSpacing.xs))
            }

            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(-90f)
            )
        }
    }
}
