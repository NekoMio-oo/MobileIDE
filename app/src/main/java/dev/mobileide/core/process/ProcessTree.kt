package dev.mobileide.core.process

import java.io.File

/**
 * Process-tree helpers shared by the build executor and the LSP runtime.
 *
 * Android's `Process` does not expose a pid, so commands are started through
 * `/system/bin/sh` which records `$$` and then `exec`s the real command: the
 * pid stays valid and stdio is inherited unchanged.
 */
object ProcessTree {

    fun createPidFile(directory: File?): File? = runCatching {
        File.createTempFile("mobileide-proc-", ".pid", directory)
    }.getOrNull()

    fun wrapWithPidFile(command: List<String>, pidFile: File): List<String> {
        val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
        val script = "echo \$\$ > \"\$1\"; shift; exec \"\$@\""
        return listOf(shell, "-c", script, "mobileide", pidFile.absolutePath) + command
    }

    /** Kills every descendant first, then the root, then the Java process. */
    fun killTree(process: Process, pidFile: File?) {
        val root = pidFile?.let { file ->
            runCatching { file.readText().trim().toLong() }.getOrNull()
        }
        if (root != null && root > 1) {
            descendants(root).asReversed().forEach(::killPid)
            killPid(root)
        }
        process.destroyForcibly()
    }

    fun descendants(rootPid: Long): List<Long> {
        val result = mutableListOf<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(rootPid)
        var visited = 0
        while (queue.isNotEmpty() && visited < 512) {
            visited++
            val pid = queue.removeFirst()
            childrenOf(pid).forEach { child ->
                if (child != rootPid && child > 1 && child !in result) {
                    result += child
                    queue += child
                }
            }
        }
        return result
    }

    fun childrenOf(pid: Long): List<Long> {
        val direct = runCatching {
            File("/proc/$pid/task/$pid/children").readText()
        }.getOrNull()
        if (direct != null) {
            return direct.trim().split(' ').mapNotNull { it.toLongOrNull() }
        }
        // Kernels without CONFIG_PROC_CHILDREN: match the ppid field in stat.
        return runCatching {
            File("/proc").listFiles().orEmpty()
                .mapNotNull { entry -> entry.name.toLongOrNull() }
                .filter { candidate -> parentOf(candidate) == pid }
        }.getOrDefault(emptyList())
    }

    private fun parentOf(pid: Long): Long? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val tail = stat.substringAfterLast(')')
        tail.trim().split(' ').getOrNull(1)?.toLongOrNull()
    }.getOrNull()

    private fun killPid(pid: Long) {
        runCatching { ProcessBuilder("kill", "-9", pid.toString()).start() }
    }
}
