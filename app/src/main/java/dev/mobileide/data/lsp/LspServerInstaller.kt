package dev.mobileide.data.lsp

import dev.mobileide.data.runtime.ArchiveExtractor
import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.data.runtime.RuntimeDownloader
import dev.mobileide.data.runtime.Sha256
import dev.mobileide.domain.lsp.LspInstallKind
import dev.mobileide.domain.lsp.LspInstallStage
import dev.mobileide.domain.lsp.LspLaunchKind
import dev.mobileide.domain.lsp.LspServerSpec
import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.ExecutionRequest
import dev.mobileide.domain.runtime.RemoteFile
import dev.mobileide.domain.runtime.RuntimeExecutor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Provisions language servers inside the Debian userland.
 *
 * apt packages are verified by apt's own signatures; the JDT archive is pinned
 * to a SHA-256 from the Eclipse download page before anything is extracted.
 * Nothing is ever written into a project directory.
 */
class LspServerInstaller(
    private val paths: LspPaths,
    private val linux: LinuxRuntime,
    private val executor: RuntimeExecutor,
    private val manifest: LspManifestStore,
    private val downloader: RuntimeDownloader,
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class Detection(
        val installed: Boolean,
        val version: String? = null,
        val detail: String? = null
    )

    data class InstallResult(val version: String, val sizeBytes: Long)

    suspend fun detect(spec: LspServerSpec): Detection {
        if (!linux.isInstalled()) {
            return Detection(false, detail = "Install the Linux environment first")
        }
        return when (spec.launchKind) {
            LspLaunchKind.DIRECT -> detectDirect(spec)
            LspLaunchKind.JDTLS -> detectJdt(spec)
        }
    }

    private suspend fun detectDirect(spec: LspServerSpec): Detection {
        val output = runGuest(listOf(spec.binary) + spec.versionArgs, 20_000L)
        if (output.exitCode != 0) {
            val hint = output.lines.lastOrNull { it.isNotBlank() }
            return Detection(false, detail = hint?.take(160))
        }
        val version = output.lines.firstOrNull { it.isNotBlank() }?.trim()?.take(120)
        return Detection(installed = true, version = version)
    }

    private suspend fun detectJdt(spec: LspServerSpec): Detection {
        val directory = paths.serverDir(spec.id)
        val launcher = launcherJar(directory)
        val record = manifest.load()[spec.id]
        if (launcher == null || record == null) {
            return Detection(false, detail = "Eclipse JDT LS is not installed")
        }
        val java = runGuest(listOf(spec.binary, "-version"), 20_000L)
        if (java.exitCode != 0) {
            return Detection(false, detail = java.lines.lastOrNull()?.take(160))
        }
        val javaVersion = java.lines.firstOrNull { it.contains("version", ignoreCase = true) }
            ?.trim()
            ?.take(120)
        return Detection(installed = true, version = record.version, detail = javaVersion)
    }

    suspend fun install(
        spec: LspServerSpec,
        onProgress: suspend (stage: LspInstallStage, message: String?, done: Long, total: Long?) -> Unit
    ): InstallResult {
        if (!linux.isInstalled()) throw IOException("Install the Linux environment first")
        paths.ensure()

        if (spec.packages.isNotEmpty()) {
            var lastMessage = ""
            var lastUpdate = 0L
            aptInstall(spec.packages) { line ->
                val now = clock()
                if (line != lastMessage && now - lastUpdate >= 250L) {
                    lastMessage = line
                    lastUpdate = now
                    onProgress(LspInstallStage.INSTALLING, line.take(160), 0L, null)
                }
            }
        }

        return when (spec.installKind) {
            LspInstallKind.APT -> {
                val detection = detect(spec)
                if (!detection.installed) {
                    throw IOException(detection.detail ?: "The server did not install correctly")
                }
                val version = detection.version ?: spec.displayName
                val size = spec.sizeMb * 1024L * 1024L
                manifest.put(
                    InstalledLspServer(
                        id = spec.id,
                        version = version,
                        installedAt = clock(),
                        path = "",
                        sizeBytes = size,
                        packages = spec.packages
                    )
                )
                onProgress(LspInstallStage.DONE, null, 0L, null)
                InstallResult(version, size)
            }

            LspInstallKind.ARCHIVE -> installArchive(spec, onProgress)
        }
    }

    private suspend fun installArchive(
        spec: LspServerSpec,
        onProgress: suspend (stage: LspInstallStage, message: String?, done: Long, total: Long?) -> Unit
    ): InstallResult {
        val url = spec.downloadUrl ?: throw IOException("No download URL")
        val checksum = spec.downloadSha256 ?: throw IOException("No checksum")
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "${spec.id}.tar.gz" }
        val remote = RemoteFile(
            url = url,
            sha256 = checksum,
            fileName = fileName,
            sizeBytes = spec.downloadSizeBytes.takeIf { it > 0 }
        )

        val archive = coroutineScope {
            var done = 0L
            var total: Long? = remote.sizeBytes
            val ticker = launch {
                while (isActive) {
                    onProgress(LspInstallStage.DOWNLOADING, fileName, done, total)
                    delay(300L)
                }
            }
            val file = try {
                downloader.download(remote) { bytesDone, bytesTotal ->
                    done = bytesDone
                    total = bytesTotal
                }
            } finally {
                ticker.cancel()
            }
            onProgress(LspInstallStage.DOWNLOADING, fileName, file.length(), file.length())
            file
        }

        onProgress(LspInstallStage.VERIFYING, fileName, archive.length(), archive.length())
        val actual = Sha256.of(archive)
        if (!actual.equals(checksum, ignoreCase = true)) {
            archive.delete()
            throw IOException("Checksum mismatch for $fileName")
        }

        val staging = File(paths.tempDir, "${spec.id}-staging-${clock()}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Could not create the staging folder")
        onProgress(LspInstallStage.EXTRACTING, fileName, 0L, null)
        try {
            ArchiveExtractor.extract(archive, staging)
            if (launcherJar(staging) == null) {
                throw IOException("The archive does not contain the Eclipse launcher")
            }
            replaceDirectory(staging, paths.serverDir(spec.id))
            archive.delete()

            val size = directorySize(paths.serverDir(spec.id))
            val version = spec.displayName
            manifest.put(
                InstalledLspServer(
                    id = spec.id,
                    version = version,
                    installedAt = clock(),
                    path = paths.serverDir(spec.id).absolutePath,
                    sizeBytes = size,
                    packages = spec.packages
                )
            )
            onProgress(LspInstallStage.DONE, null, size, size)
            return InstallResult(version, size)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Removes server files; returns false when the packages must stay. */
    suspend fun uninstall(spec: LspServerSpec): Boolean {
        return when (spec.installKind) {
            LspInstallKind.APT -> {
                // The packages live in the shared Debian userland: removing them
                // could break the compiler toolchain. Only the record is dropped.
                manifest.remove(spec.id)
                false
            }

            LspInstallKind.ARCHIVE -> {
                paths.serverDir(spec.id).deleteRecursively()
                manifest.remove(spec.id)
                true
            }
        }
    }

    private suspend fun aptInstall(packages: List<String>, onLine: suspend (String) -> Unit) {
        val update = linux.buildExecution(
            guestCommand = listOf(
                "apt-get",
                "-o", "Acquire::Check-Valid-Until=false",
                "-o", "APT::Sandbox::User=root",
                "update"
            ),
            fakeRoot = true,
            timeoutMs = 300_000L,
            extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive")
        ) ?: throw IOException("The Linux environment is not available")
        val updateRun = run(update, onLine)
        if (updateRun.exitCode != 0) {
            throw IOException("apt update failed: ${updateRun.tail()}")
        }

        val install = linux.buildExecution(
            guestCommand = listOf(
                "apt-get",
                "-o", "Acquire::Check-Valid-Until=false",
                "-o", "APT::Sandbox::User=root",
                "install", "-y", "--no-install-recommends"
            ) + packages,
            fakeRoot = true,
            timeoutMs = 900_000L,
            extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive")
        ) ?: throw IOException("The Linux environment is not available")
        val installRun = run(install, onLine)
        if (installRun.exitCode != 0) {
            throw IOException("apt install failed: ${installRun.tail()}")
        }
    }

    private suspend fun run(
        request: ExecutionRequest,
        onLine: suspend (String) -> Unit = {}
    ): GuestRun {
        val lines = ArrayDeque<String>()
        var exit = -1
        executor.execute(request).collect { event ->
            when (event) {
                is ExecutionEvent.Output -> {
                    val line = event.line.trim()
                    if (line.isNotEmpty()) {
                        lines.addLast(line)
                        while (lines.size > 400) lines.removeFirst()
                        onLine(line)
                    }
                }

                is ExecutionEvent.Exit -> exit = event.code
            }
        }
        return GuestRun(exit, lines.toList())
    }

    private suspend fun runGuest(command: List<String>, timeoutMs: Long): GuestRun {
        val request = linux.buildExecution(guestCommand = command, timeoutMs = timeoutMs)
            ?: return GuestRun(-1, emptyList())
        return run(request)
    }

    private fun replaceDirectory(staging: File, target: File) {
        target.parentFile?.mkdirs()
        val backup = File(target.parentFile, "${target.name}.old-${clock()}")
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) {
            throw IOException("Could not move the previous installation aside")
        }
        if (!staging.renameTo(target)) {
            if (hadTarget) backup.renameTo(target)
            throw IOException("Could not move the server into place")
        }
        backup.deleteRecursively()
    }

    private fun launcherJar(directory: File): File? {
        val plugins = File(directory, "plugins")
        if (!plugins.isDirectory) return null
        return plugins.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("org.eclipse.equinox.launcher_") }
            .maxByOrNull { it.name }
    }

    private fun directorySize(directory: File): Long {
        if (!directory.exists()) return 0L
        var total = 0L
        directory.walkTopDown().forEach { file ->
            if (file.isFile) total += file.length()
        }
        return total
    }

    private data class GuestRun(val exitCode: Int, val lines: List<String>) {
        fun tail(): String = lines.takeLast(3).joinToString(" | ").take(240)
    }
}
