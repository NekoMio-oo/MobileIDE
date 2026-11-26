package dev.mobileide.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.ActionTile
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing

/**
 * The two primary actions. They sit side by side with equal weight, and stack
 * vertically on narrow screens or when the content column gets too tight.
 */
@Composable
fun QuickActions(
    onNewProject: () -> Unit,
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stackVertically = maxWidth < 340.dp

        if (stackVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.md)) {
                NewProjectTile(Modifier.fillMaxWidth(), onNewProject)
                OpenProjectTile(Modifier.fillMaxWidth(), onOpenProject)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(MobileIdeSpacing.md)) {
                NewProjectTile(Modifier.weight(1f), onNewProject)
                OpenProjectTile(Modifier.weight(1f), onOpenProject)
            }
        }
    }
}

@Composable
private fun NewProjectTile(modifier: Modifier, onClick: () -> Unit) {
    ActionTile(
        iconRes = R.drawable.ic_file_plus,
        title = stringResource(R.string.home_new_project_title),
        description = stringResource(R.string.home_new_project_description),
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun OpenProjectTile(modifier: Modifier, onClick: () -> Unit) {
    ActionTile(
        iconRes = R.drawable.ic_folder_open,
        title = stringResource(R.string.home_open_project_title),
        description = stringResource(R.string.home_open_project_description),
        onClick = onClick,
        modifier = modifier
    )
}
