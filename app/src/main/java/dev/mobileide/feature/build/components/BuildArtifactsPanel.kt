package dev.mobileide.feature.build.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.core.ui.formatFileSize
import dev.mobileide.domain.build.BuildArtifact
import dev.mobileide.domain.files.FileType
import dev.mobileide.feature.build.formatBuildTime

@Composable
fun BuildArtifactsPanel(
    artifacts: List<BuildArtifact>,
    onOpenFolder: (String) -> Unit,
    onDelete: (BuildArtifact) -> Unit,
    modifier: Modifier = Modifier
) {
    if (artifacts.isEmpty()) {
        EmptyState(
            iconRes = R.drawable.ic_package,
            title = stringResource(R.string.build_tab_artifacts),
            description = stringResource(R.string.build_empty_artifacts),
            modifier = modifier
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(artifacts) { artifact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_package),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artifact.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = FileType.fromFileName(artifact.name).displayName +
                            " · " + formatFileSize(artifact.sizeBytes) +
                            " · " + formatBuildTime(artifact.modifiedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artifact.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val folder = artifact.path.substringBeforeLast('/', "")
                IconButton(
                    onClick = { onOpenFolder(folder) },
                    enabled = folder.isNotEmpty()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder_open),
                        contentDescription = stringResource(R.string.build_artifact_open_folder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onDelete(artifact) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.build_artifact_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}
