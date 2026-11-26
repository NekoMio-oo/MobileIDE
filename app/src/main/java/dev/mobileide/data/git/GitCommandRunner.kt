package dev.mobileide.data.git

/**
 * Options for one Git invocation. [readOnly] lets the runner disable optional
 * index locks so status/diff/log never modify the repository.
 */
data class GitRunOptions(
    val timeoutMs: Long = 60_000L,
    val readOnly: Boolean = true,
    val extraEnv: Map<String, String> = emptyMap(),
    /** Extra `host:guest` mounts, used for askpass and SSH material. */
    val extraBinds: List<String> = emptyList(),
    /** Receives stderr segments split on CR/LF, for fetch/push progress. */
    val onProgress: ((String) -> Unit)? = null
)

/**
 * Raw result of one Git invocation. stdout is kept byte-for-byte so NUL
 * separated porcelain output stays parseable; the cap flags tell callers when
 * they only received a prefix.
 */
data class GitCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false
) {
    val isSuccess: Boolean
        get() = exitCode == 0
}

/**
 * Runs `git` inside the project environment. Implementations must never build a
 * shell command line: [args] is passed as argv.
 *
 * A null [projectPath] runs outside any project (used for `git --version`).
 */
interface GitCommandRunner {
    suspend fun run(
        projectPath: String?,
        args: List<String>,
        options: GitRunOptions = GitRunOptions()
    ): GitCommandResult
}
