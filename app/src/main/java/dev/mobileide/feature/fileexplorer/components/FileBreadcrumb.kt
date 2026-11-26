package dev.mobileide.feature.fileexplorer.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing

/**
 * Tappable path trail. Every segment jumps back to that folder; the trailing
 * "up" button is the fast path for deep trees.
 */
@Composable
fun FileBreadcrumb(
    projectName: String,
    currentPath: String,
    canGoUp: Boolean,
    onUp: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segments = remember(currentPath) {
        currentPath.split('/').filter { it.isNotBlank() }
    }
    val scrollState = rememberScrollState()

    LaunchedEffect(currentPath, segments.size) {
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MobileIdeSpacing.xs, end = MobileIdeSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onUp, enabled = canGoUp) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_up),
                contentDescription = stringResource(R.string.explorer_up),
                tint = if (canGoUp) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
                modifier = Modifier.size(19.dp)
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BreadcrumbSegment(
                label = projectName,
                isCurrent = segments.isEmpty(),
                onClick = { onNavigate("") }
            )
            segments.forEachIndexed { index, segment ->
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                BreadcrumbSegment(
                    label = segment,
                    isCurrent = index == segments.lastIndex,
                    onClick = {
                        onNavigate(segments.take(index + 1).joinToString("/"))
                    }
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbSegment(
    label: String,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.primary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .then(
                if (isCurrent) Modifier else Modifier.clickableNoRipple(onClick)
            )
    )
}

/** Keeps the text-only breadcrumb tappable without a background highlight. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = clickable(
    interactionSource = null,
    indication = null,
    onClick = onClick
)
