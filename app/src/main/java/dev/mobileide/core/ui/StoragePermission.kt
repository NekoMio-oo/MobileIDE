package dev.mobileide.core.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mobileide.R
import dev.mobileide.core.storage.AllFilesAccess

data class StoragePermissionState(
    val granted: Boolean,
    val request: () -> Unit
)

/**
 * Tracks the special "All files access" grant. On API 30+ the only entry point
 * is the Settings screen, so the state is refreshed whenever the screen
 * resumes; API 26-29 uses a normal runtime storage permission.
 */
@Composable
fun rememberStoragePermissionState(allFilesAccess: AllFilesAccess): StoragePermissionState {
    var granted by remember { mutableStateOf(allFilesAccess.hasAccess()) }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { granted = allFilesAccess.hasAccess() }

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, allFilesAccess) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = allFilesAccess.hasAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return StoragePermissionState(
        granted = granted,
        request = {
            if (allFilesAccess.needsRuntimePermission()) {
                runtimeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                settingsLauncher.launch(allFilesAccess.settingsIntent())
            }
        }
    )
}

@Composable
fun StoragePermissionBanner(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.storage_permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.storage_permission_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onGrant) {
                Text(
                    text = stringResource(R.string.storage_permission_grant),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
