package dev.mobileide.feature.git

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.PlaceholderScreen
import dev.mobileide.core.designsystem.component.ScreenHeader

@Composable
fun GitScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.git_title))
        PlaceholderScreen(
            iconRes = R.drawable.ic_git,
            title = stringResource(R.string.git_title),
            description = stringResource(R.string.git_description),
            modifier = Modifier.weight(1f)
        )
    }
}
