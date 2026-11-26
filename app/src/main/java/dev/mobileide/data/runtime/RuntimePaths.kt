package dev.mobileide.data.runtime

import java.io.File
import java.nio.file.Files

/**
 * Application-private runtime layout. Kept strictly separate from user projects.
 *
 * files/runtime/<id>/          installed environment files
 * cache/runtime-downloads/     verified downloads (deleted after install)
 * cache/runtime-temp/          staging / proot temp
 * files/runtime-config/        catalog override + install manifest
 */
class RuntimePaths(filesDir: File, cacheDir: File) {

    val runtimeDir = File(filesDir, "runtime")
    val linuxDir = File(runtimeDir, "linux")
    val rootfsDir = File(linuxDir, "rootfs")
    val prootDir = File(linuxDir, "proot")

    val downloadsDir = File(cacheDir, "runtime-downloads")
    val tempDir = File(cacheDir, "runtime-temp")

    val configDir = File(filesDir, "runtime-config")
    val catalogOverride = File(configDir, "catalog.properties")
    val manifestFile = File(configDir, "installed.properties")

    /** The extracted Termux .deb tree. */
    val prootPrefix: File
        get() = File(prootDir, "data/data/com.termux/files/usr")

    val prootBinary: File
        get() = File(prootPrefix, "bin/proot")

    val prootLoader: File
        get() = File(prootPrefix, "libexec/proot/loader")

    val prootLibDir: File
        get() = File(prootPrefix, "lib")

    val rootfsShell: File
        get() = File(rootfsDir, "bin/sh")

    val rootfsUsrShell: File
        get() = File(rootfsDir, "usr/bin/sh")

    fun ensureBaseDirs() {
        runtimeDir.mkdirs()
        linuxDir.mkdirs()
        downloadsDir.mkdirs()
        tempDir.mkdirs()
        configDir.mkdirs()
    }
}

/** Recursive size without following symbolic links. */
fun File.recursiveSize(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    if (Files.isSymbolicLink(toPath())) return 0L
    val children = listFiles() ?: return 0L
    return children.sumOf { it.recursiveSize() }
}

/** Deletes a tree without following symbolic links. */
fun File.deleteTree() {
    val symbolicLink = Files.isSymbolicLink(toPath())
    if (!exists() && !symbolicLink) return
    if (symbolicLink || isFile) {
        delete()
        return
    }
    listFiles()?.forEach { it.deleteTree() }
    delete()
}
