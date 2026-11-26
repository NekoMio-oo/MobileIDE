package dev.mobileide.feature.build.model

import dev.mobileide.domain.build.BuildArtifact
import dev.mobileide.domain.build.BuildConfig
import dev.mobileide.domain.build.BuildProblem
import dev.mobileide.domain.build.BuildSnapshot
import dev.mobileide.domain.build.BuildTask

enum class BuildPanel {
    LOG,
    PROBLEMS,
    ARTIFACTS
}

data class UnsavedBuildPrompt(val fileNames: List<String>)

data class BuildUiState(
    val projectId: Long = 0L,
    val snapshot: BuildSnapshot = BuildSnapshot(isLoading = false),
    val panel: BuildPanel = BuildPanel.LOG,
    val autoScroll: Boolean = true,
    val showHistory: Boolean = false,
    val deleteRequest: BuildArtifact? = null,
    val unsavedPrompt: UnsavedBuildPrompt? = null,
    val message: String? = null
) {
    val config: BuildConfig get() = snapshot.config
    val task: BuildTask? get() = snapshot.task
    val isBuilding: Boolean get() = snapshot.isBuilding
    val problems: List<BuildProblem> get() = snapshot.problems

    val errorCount: Int
        get() = snapshot.task?.errorCount ?: problems.count {
            it.severity == dev.mobileide.domain.build.BuildProblemSeverity.ERROR
        }

    val warningCount: Int
        get() = snapshot.task?.warningCount ?: problems.count {
            it.severity == dev.mobileide.domain.build.BuildProblemSeverity.WARNING
        }
}
