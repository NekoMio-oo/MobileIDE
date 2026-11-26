package dev.mobileide.feature.build.components

import androidx.compose.foundation.clickable
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
import dev.mobileide.domain.build.BuildProblem
import dev.mobileide.domain.build.BuildProblemSeverity

/** Compiler diagnostics; rows with a resolvable path open the editor. */
@Composable
fun BuildProblemsPanel(
    problems: List<BuildProblem>,
    onOpen: (BuildProblem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (problems.isEmpty()) {
        EmptyState(
            iconRes = R.drawable.ic_check_circle,
            title = stringResource(R.string.build_tab_problems),
            description = stringResource(R.string.build_empty_problems),
            modifier = modifier
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(problems) { problem ->
            val clickable = problem.path != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = clickable) { onOpen(problem) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    painter = painterResource(
                        if (problem.severity == BuildProblemSeverity.ERROR) {
                            R.drawable.ic_error
                        } else {
                            R.drawable.ic_warning
                        }
                    ),
                    contentDescription = null,
                    tint = if (problem.severity == BuildProblemSeverity.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = problem.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = problem.path?.let { path ->
                            buildString {
                                append(path)
                                append(':')
                                append(problem.line)
                                problem.column?.let {
                                    append(':')
                                    append(it)
                                }
                            }
                        } ?: problem.raw,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}
