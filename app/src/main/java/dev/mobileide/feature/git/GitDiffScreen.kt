package dev.mobileide.feature.git

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobileide.R
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.domain.git.GitDiff
import dev.mobileide.domain.git.GitDiffFile
import dev.mobileide.domain.git.GitDiffLine
import dev.mobileide.domain.git.GitDiffLineKind
import dev.mobileide.feature.git.model.GitDiffUiState

/**
 * Full-screen unified diff. Long lines soft-wrap instead of forcing a
 * horizontal scroller, which reads better on a phone; the two gutters keep
 * old/new line numbers aligned.
 */
@Composable
fun GitDiffScreen(
    state: GitDiffUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFile: ((String) -> Unit)? = null
) {
    val diff = state.diff
    val file = diff?.files?.firstOrNull()

    Column(modifier = modifier.fillMaxSize()) {
        GitDiffTopBar(
            path = state.request.path,
            staged = state.request.staged,
            commitHash = state.request.commitHash,
            diff = diff,
            onBack = onBack,
            onOpenFile = onOpenFile
        )

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            state.error != null -> GitDiffError(
                message = state.error.message,
                detail = state.error.detail,
                onRetry = onRetry
            )

            file == null -> GitDiffNotice(
                iconRes = R.drawable.ic_info,
                title = stringResource(R.string.git_diff_empty_title),
                description = stringResource(R.string.git_diff_empty_description)
            )

            file.isBinary -> GitDiffNotice(
                iconRes = R.drawable.ic_info,
                title = stringResource(R.string.git_diff_binary_title),
                description = stringResource(R.string.git_diff_binary_description)
            )

            file.isTooLarge -> GitDiffNotice(
                iconRes = R.drawable.ic_info,
                title = stringResource(R.string.git_diff_too_large_title),
                description = stringResource(R.string.git_diff_too_large_description)
            )

            file.hunks.isEmpty() -> GitDiffNotice(
                iconRes = R.drawable.ic_info,
                title = stringResource(R.string.git_diff_empty_title),
                description = stringResource(R.string.git_diff_empty_description)
            )

            else -> GitDiffBody(file = file, truncated = diff?.truncated == true)
        }
    }
}

@Composable
private fun GitDiffTopBar(
    path: String,
    staged: Boolean,
    commitHash: String?,
    diff: GitDiff?,
    onBack: () -> Unit,
    onOpenFile: ((String) -> Unit)?
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
                    text = path,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        commitHash != null -> stringResource(
                            R.string.git_diff_commit_mode,
                            shortGitHash(commitHash)
                        )

                        staged -> stringResource(R.string.git_diff_staged_mode)
                        else -> stringResource(R.string.git_diff_worktree_mode)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (diff != null) {
                Text(
                    text = stringResource(
                        R.string.git_diff_stats,
                        diff.addedLines,
                        diff.removedLines
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onOpenFile != null && commitHash == null) {
                IconButton(onClick = { onOpenFile(path) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_code),
                        contentDescription = stringResource(R.string.git_open_file),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private sealed interface DiffRow {
    data class Header(val text: String) : DiffRow
    data class Line(val line: GitDiffLine) : DiffRow
}

@Composable
private fun GitDiffBody(file: GitDiffFile, truncated: Boolean) {
    val rows = remember(file, truncated) {
        buildList {
            file.hunks.forEach { hunk ->
                add(DiffRow.Header(hunk.header))
                hunk.lines.forEach { line -> add(DiffRow.Line(line)) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = MobileIdeSpacing.xs)
    ) {
        items(rows.size) { index ->
            when (val row = rows[index]) {
                is DiffRow.Header -> DiffHunkHeader(row.text)
                is DiffRow.Line -> DiffLineRow(row.line)
            }
        }
        if (truncated) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MobileIdeSpacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.git_diff_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffHunkHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = MobileIdeSpacing.md, vertical = MobileIdeSpacing.sm),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun DiffLineRow(line: GitDiffLine) {
    val background = when (line.kind) {
        GitDiffLineKind.ADDED -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        GitDiffLineKind.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        GitDiffLineKind.CONTEXT -> Color.Transparent
    }
    val prefix = when (line.kind) {
        GitDiffLineKind.ADDED -> "+"
        GitDiffLineKind.REMOVED -> "-"
        GitDiffLineKind.CONTEXT -> " "
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = MobileIdeSpacing.sm, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = line.oldLine?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(34.dp)
        )
        Text(
            text = line.newLine?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(34.dp)
        )
        Spacer(Modifier.width(MobileIdeSpacing.xs))
        Text(
            text = prefix,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = when (line.kind) {
                GitDiffLineKind.ADDED -> MaterialTheme.colorScheme.tertiary
                GitDiffLineKind.REMOVED -> MaterialTheme.colorScheme.error
                GitDiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(10.dp)
        )
        Text(
            text = line.text.replace("\t", "    ").ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GitDiffNotice(
    iconRes: Int,
    title: String,
    description: String
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
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(MobileIdeSpacing.lg))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(MobileIdeSpacing.xs))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GitDiffError(
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
