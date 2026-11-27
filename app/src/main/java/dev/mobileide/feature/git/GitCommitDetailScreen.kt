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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.domain.git.GitChangeKind
import dev.mobileide.domain.git.GitCommitDetail
import dev.mobileide.domain.git.GitCommitFile
import dev.mobileide.feature.git.model.GitCommitDetailUiState

/** Read-only commit detail with metadata, full message and changed files. */
@Composable
fun GitCommitDetailScreen(
    state: GitCommitDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenParent: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val detail = state.detail
    Column(modifier = modifier.fillMaxSize()) {
        GitCommitTopBar(
            shortHash = detail?.commit?.shortHash ?: shortGitHash(state.hash),
            subject = detail?.commit?.subject.orEmpty(),
            onBack = onBack
        )

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            state.error != null -> CommitDetailError(
                message = state.error.message,
                detail = state.error.detail,
                onRetry = onRetry
            )

            detail == null -> CommitDetailError(
                message = stringResource(R.string.git_error_unknown),
                detail = null,
                onRetry = onRetry
            )

            else -> CommitDetailContent(
                detail = detail,
                onOpenParent = onOpenParent,
                onOpenFile = onOpenFile
            )
        }
    }
}

@Composable
private fun GitCommitTopBar(
    shortHash: String,
    subject: String,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(
                    start = MobileIdeSpacing.xs,
                    end = MobileIdeSpacing.sm,
                    top = MobileIdeSpacing.xs,
                    bottom = MobileIdeSpacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MobileIdeSpacing.sm)
            ) {
                Text(
                    text = shortHash,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subject.isNotBlank()) {
                    Text(
                        text = subject,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitDetailContent(
    detail: GitCommitDetail,
    onOpenParent: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    Box(
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
                top = MobileIdeSpacing.sm,
                bottom = MobileIdeSpacing.xxxl
            ),
            verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.md)
        ) {
            item(key = "meta") {
                CommitMetaCard(detail = detail, onOpenParent = onOpenParent)
            }
            item(key = "message") {
                CommitMessageCard(message = detail.message, subject = detail.commit.subject)
            }
            item(key = "files-header") {
                Text(
                    text = stringResource(
                        R.string.git_commit_detail_files_count,
                        detail.files.size
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (detail.files.isEmpty()) {
                item(key = "files-empty") {
                    Text(
                        text = stringResource(R.string.git_commit_detail_no_files),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(items = detail.files, key = { "${it.change}:${it.path}" }) { file ->
                    CommitFileRow(file = file, onClick = { onOpenFile(file.path) })
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
            if (detail.truncated) {
                item(key = "truncated") {
                    Text(
                        text = stringResource(R.string.git_commit_detail_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitMetaCard(
    detail: GitCommitDetail,
    onOpenParent: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.lg)) {
            CommitDetailRow(
                label = stringResource(R.string.git_commit_detail_author),
                value = "${detail.commit.authorName} <${detail.commit.authorEmail}>"
            )
            CommitDetailRow(
                label = stringResource(R.string.git_commit_detail_date),
                value = formatGitDate(detail.commit.date)
            )
            if (detail.commit.refs.isNotEmpty()) {
                CommitDetailRow(
                    label = stringResource(R.string.git_commit_detail_refs),
                    value = detail.commit.refs.joinToString(" · ")
                )
            }
            if (detail.branches.isNotEmpty()) {
                CommitDetailRow(
                    label = stringResource(R.string.git_commit_detail_branches),
                    value = detail.branches.joinToString(" · ")
                )
            }
            ParentsRow(parents = detail.commit.parents, onOpenParent = onOpenParent)
            if (detail.commit.isMerge) {
                Spacer(Modifier.height(MobileIdeSpacing.xs))
                Text(
                    text = stringResource(R.string.git_commit_detail_merge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ParentsRow(
    parents: List<String>,
    onOpenParent: (String) -> Unit
) {
    if (parents.isEmpty()) {
        CommitDetailRow(
            label = stringResource(R.string.git_commit_detail_parents),
            value = stringResource(R.string.git_commit_detail_root)
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = stringResource(R.string.git_commit_detail_parents),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(MobileIdeSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            parents.forEach { parent ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.clickable { onOpenParent(parent) }
                ) {
                    Text(
                        text = shortGitHash(parent),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.height(MobileIdeSpacing.xs))
            }
        }
    }
}

@Composable
private fun CommitMessageCard(message: String, subject: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.lg)) {
            Text(
                text = stringResource(R.string.git_commit_detail_message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MobileIdeSpacing.sm))
            SelectionContainer {
                Text(
                    text = message.ifBlank { subject },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CommitFileRow(file: GitCommitFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = MobileIdeSpacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(gitChangeColor(file.change))
        )
        Spacer(Modifier.width(MobileIdeSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (file.change == GitChangeKind.RENAMED &&
                    !file.oldPath.isNullOrBlank()
                ) {
                    "${file.oldPath} → ${file.path}"
                } else {
                    file.path
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = gitChangeKindLabel(file.change),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

@Composable
private fun CommitDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(MobileIdeSpacing.sm))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CommitDetailError(
    message: String,
    detail: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = MobileIdeDimens.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(MobileIdeSpacing.lg))
            Text(
                text = message,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            detail?.let {
                Spacer(Modifier.height(MobileIdeSpacing.md))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    SelectionContainer {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MobileIdeSpacing.md),
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(MobileIdeSpacing.lg))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.git_retry))
            }
        }
    }
}
