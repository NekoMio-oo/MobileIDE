package dev.mobileide.data.lsp

import java.io.File

/**
 * Private storage for language servers. Nothing here is ever written into a
 * project directory: servers, their caches and their logs belong to the app.
 */
class LspPaths(filesDir: File, cacheDir: File) {

    val baseDir = File(filesDir, "lsp")
    val serversDir = File(baseDir, "servers")
    val homeDir = File(baseDir, "home")
    val workspacesDir = File(baseDir, "workspaces")

    val configDir = File(filesDir, "lsp-config")
    val installedFile = File(configDir, "installed.properties")
    val settingsFile = File(configDir, "settings.properties")
    val catalogOverride = File(configDir, "catalog.properties")

    val downloadsDir = File(cacheDir, "lsp-downloads")
    val tempDir = File(cacheDir, "lsp-temp")

    fun serverDir(id: String): File {
        require(ID_PATTERN.matches(id)) { "Invalid server id: $id" }
        return File(serversDir, id)
    }

    fun workspaceDir(serverId: String, projectId: Long): File {
        require(ID_PATTERN.matches(serverId)) { "Invalid server id: $serverId" }
        return File(workspacesDir, "$serverId-$projectId")
    }

    fun ensure() {
        serversDir.mkdirs()
        homeDir.mkdirs()
        workspacesDir.mkdirs()
        configDir.mkdirs()
        downloadsDir.mkdirs()
        tempDir.mkdirs()
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,40}")
    }
}
