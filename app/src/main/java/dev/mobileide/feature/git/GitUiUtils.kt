package dev.mobileide.feature.git

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.mobileide.R
import dev.mobileide.domain.git.GitChangeKind
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun gitChangeKindLabel(kind: GitChangeKind): String = when (kind) {
    GitChangeKind.MODIFIED -> stringResource(R.string.git_change_modified)
    GitChangeKind.ADDED -> stringResource(R.string.git_change_added)
    GitChangeKind.DELETED -> stringResource(R.string.git_change_deleted)
    GitChangeKind.RENAMED -> stringResource(R.string.git_change_renamed)
    GitChangeKind.COPIED -> stringResource(R.string.git_change_copied)
    GitChangeKind.TYPE_CHANGED -> stringResource(R.string.git_change_type_changed)
    GitChangeKind.UNTRACKED -> stringResource(R.string.git_change_untracked)
    GitChangeKind.CONFLICTED -> stringResource(R.string.git_change_conflicted)
    GitChangeKind.UNKNOWN -> stringResource(R.string.git_change_unknown)
}

@Composable
internal fun gitChangeColor(kind: GitChangeKind): Color = when (kind) {
    GitChangeKind.CONFLICTED,
    GitChangeKind.DELETED -> MaterialTheme.colorScheme.error

    GitChangeKind.UNTRACKED,
    GitChangeKind.ADDED -> MaterialTheme.colorScheme.tertiary

    GitChangeKind.RENAMED,
    GitChangeKind.COPIED -> MaterialTheme.colorScheme.secondary

    else -> MaterialTheme.colorScheme.primary
}

/** ISO author date from git, rendered in the device's time zone. */
internal fun formatGitDate(value: String): String = runCatching {
    OffsetDateTime.parse(value)
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(COMMIT_DATE_FORMAT)
}.getOrDefault(value)

internal fun shortGitHash(hash: String): String = hash.take(SHORT_HASH_LENGTH)

private val COMMIT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val SHORT_HASH_LENGTH = 7
