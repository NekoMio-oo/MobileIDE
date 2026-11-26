package dev.mobileide.feature.build

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.mobileide.di.container
import dev.mobileide.domain.build.BuildActionResult
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.BuildRepository
import dev.mobileide.domain.build.BuildStandards
import dev.mobileide.feature.build.model.BuildConfigUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edits one project's build configuration. Compiler and standard lists come from
 * the real toolchain probe, never from a hardcoded catalogue.
 */
class BuildConfigViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repository: BuildRepository = application.container.buildRepository
    private val projectId: Long =
        savedStateHandle.get<String>("projectId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(BuildConfigUiState())
    val uiState: StateFlow<BuildConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        repository.load(projectId)
        val snapshot = repository.observe(projectId).value
        val config = snapshot.config
        val sources = repository.availableSources(projectId, config.language)
        // load() already probed this language; only probe again if it found none.
        val compilers = snapshot.toolchain.compilers.ifEmpty {
            repository.availableCompilers(projectId, config.language)
        }
        _uiState.value = BuildConfigUiState(
            isLoading = false,
            language = config.language,
            mode = config.mode,
            compiler = config.compiler.takeIf { it in compilers } ?: compilers.firstOrNull().orEmpty(),
            compilers = compilers,
            standard = config.standard.ifBlank { BuildStandards.defaultFor(config.language) },
            outputName = config.outputName,
            sourcesAuto = config.sourcesAuto,
            sources = sources,
            selectedSources = if (config.sourcesAuto) {
                sources.toSet()
            } else {
                config.sources.filter { it in sources }.toSet()
            },
            entryFile = config.entryFile.orEmpty(),
            includeDirs = config.includeDirs.joinToString("\n"),
            libraryDirs = config.libraryDirs.joinToString("\n"),
            libraries = config.libraries.joinToString("\n"),
            compileFlags = config.compileFlags.joinToString("\n"),
            linkFlags = config.linkFlags.joinToString("\n"),
            classpath = config.classpath.joinToString("\n"),
            target = config.target.ifBlank { BuildStandards.defaultFor(config.language) }
        )
    }

    fun selectLanguage(language: BuildLanguage) {
        if (language == _uiState.value.language) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(language = language, isProbing = true, saved = false, error = null)
            }
            val sources = repository.availableSources(projectId, language)
            val compilers = repository.availableCompilers(projectId, language)
            _uiState.update {
                it.copy(
                    isProbing = false,
                    sources = sources,
                    sourcesAuto = true,
                    selectedSources = sources.toSet(),
                    compiler = compilers.firstOrNull().orEmpty(),
                    compilers = compilers,
                    standard = BuildStandards.defaultFor(language),
                    target = BuildStandards.defaultFor(language),
                    entryFile = ""
                )
            }
        }
    }

    fun selectMode(mode: BuildMode) = _uiState.update { it.copy(mode = mode, saved = false) }

    fun selectCompiler(compiler: String) =
        _uiState.update { it.copy(compiler = compiler, saved = false) }

    fun selectStandard(standard: String) =
        _uiState.update { it.copy(standard = standard, saved = false) }

    fun selectTarget(target: String) =
        _uiState.update { it.copy(target = target, saved = false) }

    fun setOutputName(value: String) = _uiState.update { it.copy(outputName = value, saved = false) }

    fun setSourcesAuto(enabled: Boolean) = _uiState.update {
        it.copy(
            sourcesAuto = enabled,
            selectedSources = if (enabled) it.sources.toSet() else it.selectedSources,
            saved = false
        )
    }

    fun toggleSource(path: String) {
        _uiState.update { state ->
            val selected = if (path in state.selectedSources) {
                state.selectedSources - path
            } else {
                state.selectedSources + path
            }
            state.copy(selectedSources = selected, saved = false)
        }
    }

    fun selectAllSources() = _uiState.update {
        it.copy(selectedSources = it.sources.toSet(), saved = false)
    }

    fun clearSources() = _uiState.update { it.copy(selectedSources = emptySet(), saved = false) }

    fun setEntryFile(path: String) = _uiState.update { it.copy(entryFile = path, saved = false) }

    fun setIncludeDirs(value: String) = _uiState.update { it.copy(includeDirs = value, saved = false) }

    fun setLibraryDirs(value: String) = _uiState.update { it.copy(libraryDirs = value, saved = false) }

    fun setLibraries(value: String) = _uiState.update { it.copy(libraries = value, saved = false) }

    fun setCompileFlags(value: String) = _uiState.update { it.copy(compileFlags = value, saved = false) }

    fun setLinkFlags(value: String) = _uiState.update { it.copy(linkFlags = value, saved = false) }

    fun setClasspath(value: String) = _uiState.update { it.copy(classpath = value, saved = false) }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            when (val result = repository.saveConfig(projectId, state.toConfig())) {
                BuildActionResult.Success -> _uiState.update {
                    it.copy(isSaving = false, saved = true)
                }

                is BuildActionResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(error = null, saved = false) }
}
