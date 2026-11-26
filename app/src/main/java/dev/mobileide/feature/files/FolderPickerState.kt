package dev.mobileide.feature.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.mobileide.domain.repository.DirectoryEntry
import dev.mobileide.domain.repository.DirectoryListingResult
import dev.mobileide.domain.repository.DirectoryValidation
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.repository.StorageBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Small state holder for the in-app folder picker. */
class FolderPickerState(
    private val browser: StorageBrowser,
    private val scope: CoroutineScope,
    startPath: String
) {
    var currentPath by mutableStateOf(startPath)
        private set
    var directories by mutableStateOf<List<DirectoryEntry>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var error by mutableStateOf<ProjectFileError?>(null)
        private set
    var errorDetail by mutableStateOf<String?>(null)
        private set
    var canSelect by mutableStateOf(false)
        private set

    val canGoUp: Boolean get() = browser.canGoUp(currentPath)

    fun load(path: String) {
        currentPath = path
        scope.launch {
            isLoading = true
            error = null
            errorDetail = null
            canSelect = false
            when (val result = browser.listDirectories(path)) {
                is DirectoryListingResult.Success -> directories = result.directories
                is DirectoryListingResult.Failure -> {
                    directories = emptyList()
                    error = result.error
                    errorDetail = result.detail
                }
            }
            canSelect = browser.validateProjectDirectory(path) == DirectoryValidation.VALID
            isLoading = false
        }
    }

    fun open(entry: DirectoryEntry) {
        load(entry.path)
    }

    fun goUp() {
        if (!canGoUp) return
        File(currentPath).parentFile?.let { load(it.absolutePath) }
    }

    fun retry() {
        load(currentPath)
    }
}

@Composable
fun rememberFolderPickerState(
    browser: StorageBrowser,
    startPath: String
): FolderPickerState {
    val scope = rememberCoroutineScope()
    val state = remember(browser, startPath) {
        FolderPickerState(browser = browser, scope = scope, startPath = startPath)
    }
    LaunchedEffect(state) {
        state.load(startPath)
    }
    return state
}
