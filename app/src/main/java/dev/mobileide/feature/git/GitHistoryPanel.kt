package dev.mobileide.feature.git

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.domain.git.GitCommit
import dev.mobileide.feature.git.model.GitHistoryUiState

/** History tab: search over the loaded commits and open a commit detail. */
@Composable
internal fun GitHistoryPanel(
    state: GitHistoryUiState,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
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
            verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.sm)
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.git_history_search_hint)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            if (state.isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MobileIdeSpacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            state.error?.let { error ->
                item(key = "error") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(MobileIdeSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = error.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                error.detail?.let { detail ->
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            TextButton(onClick = onRetry) {
                                Text(
                                    text = stringResource(R.string.git_retry),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            if (!state.isLoading && state.error == null && state.commits.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        iconRes = R.drawable.ic_git,
                        title = stringResource(
                            if (state.query.isBlank()) {
                                R.string.git_history_empty_title
                            } else {
                                R.string.git_history_no_matches_title
                            }
                        ),
                        description = stringResource(
                            if (state.query.isBlank()) {
                                R.string.git_history_empty_description
                            } else {
                                R.string.git_history_no_matches_description
                            }
                        )
                    )
                }
            }

            items(items = state.commits, key = { it.hash }) { commit ->
                GitCommitRow(commit = commit, onClick = { onOpenCommit(commit.hash) })
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            if (state.truncated) {
                item(key = "truncated") {
                    Text(
                        text = stringResource(R.string.git_history_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MobileIdeSpacing.sm)
                    )
                }
            }
        }
    }
}

@Composable
private fun GitCommitRow(commit: GitCommit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(MobileIdeSpacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = commit.shortHash,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(MobileIdeSpacing.sm))
            Text(
                text = formatGitDate(commit.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(MobileIdeSpacing.xs))
        Text(
            text = commit.subject.ifBlank { commit.shortHash },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(MobileIdeSpacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = commit.authorName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (commit.refs.isNotEmpty()) {
                Spacer(Modifier.width(MobileIdeSpacing.sm))
                Text(
                    text = commit.refs.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
