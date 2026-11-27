package dev.mobileide.data.git

import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.domain.git.GitActionResult
import dev.mobileide.domain.git.GitError
import dev.mobileide.domain.git.GitErrorKind
import dev.mobileide.domain.git.GitSshKeyInfo
import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.RuntimeExecutor
import java.io.File

interface SshKeyManager {
    fun info(): GitSshKeyInfo
    suspend fun generate(): GitActionResult
    fun importKey(
        privateKey: String,
        publicKey: String?,
        passphrase: String?
    ): GitActionResult
    fun delete(): GitActionResult
}

/**
 * SSH key lifecycle. Keys live in app-private storage and are bind-mounted into
 * the Debian userland; the generated key has no passphrase (the UI warns), while
 * imported passphrases are encrypted by [GitCredentialStore].
 */
class GitSshManager(
    private val linux: LinuxRuntime,
    private val executor: RuntimeExecutor,
    private val paths: GitPaths,
    private val credentials: GitCredentialStore
) : SshKeyManager {

    override fun info(): GitSshKeyInfo {
        val publicKey = if (paths.publicKeyFile.isFile) {
            runCatching { paths.publicKeyFile.readText().trim() }.getOrNull()
        } else {
            null
        }
        return GitSshKeyInfo(
            hasKey = paths.privateKeyFile.isFile,
            publicKey = publicKey?.takeIf { it.isNotEmpty() },
            privateKeyPath = paths.privateKeyFile.absolutePath,
            hasPassphrase = credentials.hasSshPassphrase()
        )
    }

    override suspend fun generate(): GitActionResult {
        if (!linux.isInstalled()) {
            return failure("Install the Linux environment first.")
        }
        paths.ensureBaseDirs()
        linux.ensureGuestDir("root/.ssh/mobileide")
        paths.privateKeyFile.delete()
        paths.publicKeyFile.delete()
        credentials.saveSshPassphrase(null)

        val request = linux.buildExecution(
            guestCommand = listOf(
                "/usr/bin/ssh-keygen",
                "-q",
                "-t", "ed25519",
                "-N", "",
                "-C", "mobileide",
                "-f", "/root/.ssh/mobileide/id_ed25519"
            ),
            fakeRoot = true,
            bindPaths = listOf("${paths.sshDir.absolutePath}:/root/.ssh/mobileide"),
            timeoutMs = GENERATE_TIMEOUT_MS
        ) ?: return failure("Could not build the key generation command.")

        var exitCode = -1
        var timedOut = false
        val tail = ArrayDeque<String>()
        executor.execute(request).collect { event ->
            when (event) {
                is ExecutionEvent.Output -> {
                    tail.addLast(event.line)
                    if (tail.size > MAX_ERROR_LINES) tail.removeFirst()
                }

                is ExecutionEvent.Exit -> {
                    exitCode = event.code
                    timedOut = event.timedOut
                }
            }
        }
        if (exitCode != 0) {
            return failure(
                if (timedOut) "SSH key generation timed out."
                else "SSH key generation failed.",
                tail.joinToString("\n").takeLast(MAX_ERROR_CHARS)
            )
        }
        if (!paths.privateKeyFile.isFile) {
            return failure("ssh-keygen did not create a private key.")
        }
        ownerOnly(paths.privateKeyFile)
        if (paths.publicKeyFile.isFile) ownerOnly(paths.publicKeyFile)
        return GitActionResult.Success
    }

    override fun importKey(
        privateKey: String,
        publicKey: String?,
        passphrase: String?
    ): GitActionResult {
        val key = privateKey.trim()
        if (key.isEmpty()) {
            return failure("Paste a private key first.")
        }
        paths.ensureBaseDirs()
        runCatching {
            paths.privateKeyFile.writeText(key + "\n")
            ownerOnly(paths.privateKeyFile)
            val publicPart = publicKey?.trim().orEmpty()
            if (publicPart.isNotEmpty()) {
                paths.publicKeyFile.writeText(publicPart + "\n")
                ownerOnly(paths.publicKeyFile)
            } else {
                paths.publicKeyFile.delete()
            }
            credentials.saveSshPassphrase(passphrase)
        }.onFailure { error ->
            return failure("Could not import the key.", error.message)
        }
        return GitActionResult.Success
    }

    override fun delete(): GitActionResult {
        paths.privateKeyFile.delete()
        paths.publicKeyFile.delete()
        credentials.saveSshPassphrase(null)
        return GitActionResult.Success
    }

    private fun ownerOnly(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun failure(message: String, detail: String? = null): GitActionResult =
        GitActionResult.Failure(
            GitError(
                kind = GitErrorKind.AUTH_FAILED,
                message = message,
                detail = detail
            )
        )

    private companion object {
        const val GENERATE_TIMEOUT_MS = 30_000L
        const val MAX_ERROR_LINES = 20
        const val MAX_ERROR_CHARS = 2_000
    }
}
