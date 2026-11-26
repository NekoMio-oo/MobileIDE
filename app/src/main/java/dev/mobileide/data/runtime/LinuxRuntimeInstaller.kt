package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.InstalledRuntimeRecord
import dev.mobileide.domain.runtime.LinuxEnvironmentSpec
import dev.mobileide.domain.runtime.RuntimeId
import dev.mobileide.domain.runtime.RuntimeProgress
import dev.mobileide.domain.runtime.RuntimeStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Downloads, verifies and installs the Debian + PRoot environment.
 *
 * Everything is staged under files/runtime/linux before it replaces an existing
 * install, so a failed download never destroys a working environment.
 */
class LinuxRuntimeInstaller(
    private val paths: RuntimePaths,
    private val downloader: RuntimeDownloader,
    private val manifest: RuntimeManifestStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun isInstalled(): Boolean =
        paths.prootBinary.isFile &&
            paths.prootLoader.isFile &&
            (paths.rootfsShell.isFile || paths.rootfsUsrShell.isFile)

    fun sizeBytes(): Long = paths.linuxDir.recursiveSize()

    suspend fun install(
        spec: LinuxEnvironmentSpec,
        onProgress: (RuntimeProgress) -> Unit
    ): InstalledRuntimeRecord = withContext(Dispatchers.IO) {
        paths.ensureBaseDirs()
        cleanupLeftovers()
        val usable = paths.linuxDir.usableSpace
        if (usable in 1 until MIN_FREE_BYTES) {
            throw IOException(
                "Not enough free space: ${usable / (1024 * 1024)} MB available, " +
                    "${MIN_FREE_BYTES / (1024 * 1024)} MB required"
            )
        }
        val artifacts = listOf(spec.rootfs, spec.proot) + spec.prootLibs
        val totalBytes = artifacts.sumOf { it.sizeBytes ?: 0L }.takeIf { it > 0 }
        val downloads = mutableListOf<File>()
        var staging: File? = null

        try {
            var completed = 0L
            artifacts.forEach { remote ->
                val file = downloader.download(remote) { done, _ ->
                    onProgress(
                        RuntimeProgress(
                            runtimeId = RuntimeId.LINUX,
                            stage = RuntimeStage.DOWNLOADING,
                            fileName = remote.fileName,
                            bytesDone = completed + done,
                            bytesTotal = totalBytes
                        )
                    )
                }
                downloads += file
                completed += file.length()

                onProgress(
                    RuntimeProgress(
                        runtimeId = RuntimeId.LINUX,
                        stage = RuntimeStage.VERIFYING,
                        fileName = remote.fileName
                    )
                )
                val actual = Sha256.of(file)
                if (!actual.equals(remote.sha256, ignoreCase = true)) {
                    throw IOException("Checksum mismatch for ${remote.fileName}")
                }
            }

            staging = File(paths.linuxDir, "staging-${clock()}")
            val rootfsStage = File(staging, "rootfs")
            val prootStage = File(staging, "proot")

            onProgress(
                RuntimeProgress(
                    runtimeId = RuntimeId.LINUX,
                    stage = RuntimeStage.EXTRACTING,
                    fileName = spec.rootfs.fileName
                )
            )
            ArchiveExtractor.extract(
                archive = downloads.first(),
                targetDir = rootfsStage,
                stripComponents = spec.rootfsStripComponents
            )

            onProgress(
                RuntimeProgress(
                    runtimeId = RuntimeId.LINUX,
                    stage = RuntimeStage.EXTRACTING,
                    fileName = spec.proot.fileName
                )
            )
            DebExtractor.extractData(downloads[1], prootStage)
            spec.prootLibs.forEachIndexed { index, remote ->
                onProgress(
                    RuntimeProgress(
                        runtimeId = RuntimeId.LINUX,
                        stage = RuntimeStage.EXTRACTING,
                        fileName = remote.fileName
                    )
                )
                DebExtractor.extractData(downloads[2 + index], prootStage)
            }

            validate(rootfsStage, prootStage)
            prepareRootfs(rootfsStage)

            onProgress(
                RuntimeProgress(
                    runtimeId = RuntimeId.LINUX,
                    stage = RuntimeStage.FINALIZING
                )
            )
            replaceDirectories(rootfsStage, prootStage)

            val record = InstalledRuntimeRecord(
                id = RUNTIME_ID,
                version = spec.version,
                installedAt = clock(),
                sha256 = spec.rootfs.sha256,
                sizeBytes = sizeBytes(),
                path = paths.linuxDir.absolutePath
            )
            manifest.put(record)
            record
        } finally {
            downloads.forEach { runCatching { it.delete() } }
            staging?.let { runCatching { it.deleteTree() } }
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        paths.linuxDir.deleteTree()
        manifest.remove(RUNTIME_ID)
    }

    private fun validate(rootfs: File, proot: File) {
        val shell = File(rootfs, "bin/sh")
        val usrShell = File(rootfs, "usr/bin/sh")
        if (!shell.exists() && !usrShell.exists()) {
            throw IOException("The root filesystem is missing /bin/sh")
        }
        val prootBinary = File(proot, "data/data/com.termux/files/usr/bin/proot")
        val loader = File(proot, "data/data/com.termux/files/usr/libexec/proot/loader")
        if (!prootBinary.isFile) throw IOException("proot binary is missing")
        if (!loader.isFile) throw IOException("proot loader is missing")
    }

    /** Provides DNS and package sources, which LXC images do not ship. */
    private fun prepareRootfs(rootfs: File) {
        File(rootfs, "etc").mkdirs()
        val resolv = File(rootfs, "etc/resolv.conf")
        if (Files.isSymbolicLink(resolv.toPath())) resolv.delete()
        if (!resolv.isFile || resolv.length() == 0L) {
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }

        val sources = File(rootfs, "etc/apt/sources.list")
        if (Files.isSymbolicLink(sources.toPath())) sources.delete()
        val aptConfig = File(rootfs, "etc/apt/apt.conf.d/99mobileide")
        aptConfig.parentFile?.mkdirs()
        aptConfig.writeText(
            "// MobileIDE: device clock may be ahead of the archive.\n" +
                "// Package signatures are still verified.\n" +
                "Acquire::Check-Valid-Until \"false\";\n"
        )

        val deb822 = File(rootfs, "etc/apt/sources.list.d/debian.sources")
        if ((!sources.isFile || sources.length() == 0L) && !deb822.isFile) {
            sources.writeText(
                """
                deb http://deb.debian.org/debian bookworm main
                deb http://deb.debian.org/debian bookworm-updates main
                deb http://security.debian.org/debian-security bookworm-security main
                """.trimIndent() + "\n"
            )
        }
    }

    private fun replaceDirectories(rootfsStage: File, prootStage: File) {
        val stamp = clock()
        val oldRootfs = File(paths.linuxDir, "rootfs.old-$stamp")
        val oldProot = File(paths.linuxDir, "proot.old-$stamp")
        val hadRootfs = paths.rootfsDir.exists()
        val hadProot = paths.prootDir.exists()

        if (hadRootfs && !paths.rootfsDir.renameTo(oldRootfs)) {
            throw IOException("Could not move the previous root filesystem aside")
        }
        if (hadProot && !paths.prootDir.renameTo(oldProot)) {
            if (hadRootfs) oldRootfs.renameTo(paths.rootfsDir)
            throw IOException("Could not move the previous proot aside")
        }

        val moved = rootfsStage.renameTo(paths.rootfsDir) &&
            prootStage.renameTo(paths.prootDir)
        if (!moved) {
            paths.rootfsDir.deleteTree()
            paths.prootDir.deleteTree()
            if (hadRootfs) oldRootfs.renameTo(paths.rootfsDir)
            if (hadProot) oldProot.renameTo(paths.prootDir)
            throw IOException("Could not move the runtime into place")
        }

        oldRootfs.deleteTree()
        oldProot.deleteTree()
        paths.prootBinary.setExecutable(true, true)
        paths.prootLoader.setExecutable(true, true)
    }

    /** Removes staging/backup directories left behind by a killed install. */
    private fun cleanupLeftovers() {
        paths.linuxDir.listFiles()?.forEach { child ->
            val name = child.name
            if (name.startsWith("staging-") ||
                name.startsWith("rootfs.old-") ||
                name.startsWith("proot.old-")
            ) {
                child.deleteTree()
            }
        }
    }

    private companion object {
        const val RUNTIME_ID = "LINUX"
        const val MIN_FREE_BYTES = 800L * 1024 * 1024
    }
}

/** Small helper used by the UI to show the catalog source without exposing files. */
fun RuntimePaths.describeRootfs(): String = rootfsDir.absolutePath
