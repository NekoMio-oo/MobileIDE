package dev.mobileide.data.lsp

import dev.mobileide.core.process.ProcessTree
import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.domain.lsp.LspLaunchKind
import dev.mobileide.domain.lsp.LspServerSpec
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Starts one language-server process inside PRoot and exposes its pipes.
 *
 * The command reuses [LinuxRuntime.buildProcessSpec], so the same bind and
 * environment rules as the build system apply. The project is mounted at
 * `/workspace` and the server's home lives in app-private storage; the project
 * is the only user data it can see.
 */
class LspServerRuntime(
    private val linux: LinuxRuntime,
    private val paths: LspPaths
) {

    data class Launch(
        val process: Process,
        val input: InputStream,
        val output: OutputStream,
        val pidFile: File?
    )

    fun launch(
        spec: LspServerSpec,
        projectId: Long,
        projectPath: String,
        extraArgs: List<String> = emptyList()
    ): Launch? {
        if (!linux.isInstalled()) return null
        paths.ensure()

        val guestCommand = when (spec.launchKind) {
            LspLaunchKind.DIRECT -> listOf(spec.binary) + spec.args + extraArgs
            LspLaunchKind.JDTLS -> jdtCommand(spec, projectId, extraArgs) ?: return null
        }

        val bindPaths = mutableListOf("$projectPath:/workspace", "${paths.homeDir.absolutePath}:/lsp-home")
        val mount = spec.guestMount
        if (!mount.isNullOrBlank()) {
            linux.ensureGuestDir(mount.trim('/'))
            bindPaths += "${paths.serverDir(spec.id).absolutePath}:$mount"
        }
        linux.ensureGuestDir("workspace")
        linux.ensureGuestDir("lsp-home")

        val command = linux.buildProcessSpec(
            guestCommand = guestCommand,
            guestWorkDir = "/workspace",
            bindPaths = bindPaths,
            extraEnv = mapOf(
                "HOME" to "/lsp-home",
                "TMPDIR" to "/lsp-home/tmp",
                "XDG_CACHE_HOME" to "/lsp-home/.cache",
                "XDG_CONFIG_HOME" to "/lsp-home/.config"
            )
        ) ?: return null

        File(paths.homeDir, "tmp").mkdirs()
        File(paths.homeDir, ".cache").mkdirs()
        File(paths.homeDir, ".config").mkdirs()
        if (spec.launchKind == LspLaunchKind.JDTLS) {
            // Eclipse writes its workspace metadata under -data; create the
            // parent so the first start does not depend on its own mkdirs.
            File(paths.homeDir, "jdtls/$projectId").mkdirs()
        }

        val pidFile = ProcessTree.createPidFile(paths.tempDir)
        val hostCommand = if (pidFile != null) {
            ProcessTree.wrapWithPidFile(command.command, pidFile)
        } else {
            command.command
        }
        val builder = ProcessBuilder(hostCommand)
        builder.directory(paths.tempDir)
        builder.environment().clear()
        builder.environment().putAll(command.environment)
        val process = try {
            builder.start()
        } catch (error: IOException) {
            pidFile?.delete()
            return null
        }
        return Launch(
            process = process,
            input = process.inputStream,
            output = process.outputStream,
            pidFile = pidFile
        )
    }

    fun stop(launch: Launch) {
        ProcessTree.killTree(launch.process, launch.pidFile)
        launch.pidFile?.delete()
        runCatching { launch.input.close() }
        runCatching { launch.output.close() }
    }

    /**
     * Mirrors Eclipse's `bin/jdtls` launcher, but with a phone-sized heap and
     * the ARM64 config folder. `-data` points into the private home so the
     * workspace index never lands in the project.
     */
    private fun jdtCommand(
        spec: LspServerSpec,
        projectId: Long,
        extraArgs: List<String>
    ): List<String>? {
        val mount = spec.guestMount ?: return null
        val directory = paths.serverDir(spec.id)
        val launcher = directory.resolve("plugins").listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("org.eclipse.equinox.launcher_") }
            .maxByOrNull { it.name }
            ?: return null
        val configDir = when {
            File(directory, "config_linux_arm").isDirectory -> "config_linux_arm"
            else -> "config_linux"
        }
        val dataDir = "/lsp-home/jdtls/$projectId"

        return listOf(
            spec.binary,
            "-Declipse.application=org.eclipse.jdt.ls.core.id1",
            "-Dosgi.bundles.defaultStartLevel=4",
            "-Declipse.product=org.eclipse.jdt.ls.core.product",
            "-Dosgi.checkConfiguration=true",
            "-Dosgi.sharedConfiguration.area=$mount/$configDir",
            "-Dosgi.sharedConfiguration.area.readOnly=true",
            "-Dosgi.configuration.cascaded=true",
            "-Xms128m",
            "-Xmx768m",
            "-XX:+UseSerialGC",
            "--add-modules=ALL-SYSTEM",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "-jar", "$mount/plugins/${launcher.name}",
            "-data", dataDir
        ) + spec.args + extraArgs
    }
}
