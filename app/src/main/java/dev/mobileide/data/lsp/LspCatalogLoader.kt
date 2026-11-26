package dev.mobileide.data.lsp

import dev.mobileide.domain.editor.EditorLanguage
import dev.mobileide.domain.lsp.LspCapabilities
import dev.mobileide.domain.lsp.LspInstallKind
import dev.mobileide.domain.lsp.LspLanguageIds
import dev.mobileide.domain.lsp.LspLaunchKind
import dev.mobileide.domain.lsp.LspServerSpec
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Properties

/**
 * Reads the language-server catalog. The bundled asset is the default; a file
 * in the app's config folder can override it field by field, so a broken mirror
 * or a new server can be added without shipping an APK.
 */
class LspCatalogLoader(
    private val openDefault: () -> InputStream?,
    private val overrideFile: File? = null
) {

    fun load(): List<LspServerSpec> {
        val properties = Properties()
        val override = overrideFile
        if (override != null && override.isFile) {
            FileInputStream(override).use { properties.load(InputStreamReader(it, Charsets.UTF_8)) }
        } else {
            openDefault()?.use { properties.load(InputStreamReader(it, Charsets.UTF_8)) }
                ?: return emptyList()
        }
        return parse(properties)
    }

    private fun parse(properties: Properties): List<LspServerSpec> {
        val count = properties.getProperty("server.count")?.trim()?.toIntOrNull() ?: 0
        val servers = mutableListOf<LspServerSpec>()
        for (index in 1..count) {
            val prefix = "server.$index."
            val id = properties.getProperty(prefix + "id")?.trim().orEmpty()
            if (id.isEmpty()) continue
            val name = properties.getProperty(prefix + "name")?.trim().orEmpty().ifEmpty { id }
            val languages = properties.getProperty(prefix + "languages")
                .orEmpty()
                .split(',')
                .mapNotNull { token -> runCatching { EditorLanguage.valueOf(token.trim()) }.getOrNull() }
            val unsupported = properties.getProperty(prefix + "unsupported")?.trim()?.takeIf { it.isNotEmpty() }
            val kind = properties.getProperty(prefix + "kind")?.trim()?.lowercase() ?: "apt"
            val installKind = if (kind == "archive") LspInstallKind.ARCHIVE else LspInstallKind.APT
            val launchKind = if (properties.getProperty(prefix + "launch")?.trim() == "jdtls") {
                LspLaunchKind.JDTLS
            } else {
                LspLaunchKind.DIRECT
            }
            val packages = properties.getProperty(prefix + "packages")
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val args = splitArgs(properties.getProperty(prefix + "args"))
            val versionArgs = splitArgs(properties.getProperty(prefix + "versionArgs"))
            val capabilities = parseCapabilities(properties.getProperty(prefix + "capabilities"))

            val spec = LspServerSpec(
                id = id,
                displayName = name,
                languages = languages,
                languageIds = languages.associateWith { LspLanguageIds.of(it) },
                installKind = installKind,
                packages = packages,
                downloadUrl = properties.getProperty(prefix + "url")?.trim()?.takeIf { it.isNotEmpty() },
                downloadSha256 = properties.getProperty(prefix + "sha256")?.trim()
                    ?.takeIf { it.isNotEmpty() },
                downloadSizeBytes = properties.getProperty(prefix + "size")?.trim()?.toLongOrNull() ?: 0L,
                binary = properties.getProperty(prefix + "binary")?.trim().orEmpty(),
                args = args,
                versionArgs = versionArgs,
                launchKind = launchKind,
                guestMount = properties.getProperty(prefix + "mount")?.trim()?.takeIf { it.isNotEmpty() },
                sizeMb = properties.getProperty(prefix + "sizeMb")?.trim()?.toLongOrNull() ?: 0L,
                docsUrl = properties.getProperty(prefix + "docs")?.trim()?.takeIf { it.isNotEmpty() },
                declaredCapabilities = capabilities,
                unsupportedReason = unsupported
            )
            if (!spec.supported) {
                servers += spec
                continue
            }
            // Drop entries that could never start instead of showing a dead
            // switch: a supported server needs a binary and an install source.
            val installable = when (installKind) {
                LspInstallKind.APT -> packages.isNotEmpty() && spec.binary.isNotEmpty()
                LspInstallKind.ARCHIVE ->
                    !spec.downloadUrl.isNullOrEmpty() && !spec.downloadSha256.isNullOrEmpty() &&
                        spec.binary.isNotEmpty() && !spec.guestMount.isNullOrEmpty()
            }
            if (installable && languages.isNotEmpty()) servers += spec
        }
        return servers
    }

    private fun splitArgs(raw: String?): List<String> =
        raw.orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseCapabilities(raw: String?): LspCapabilities {
        val tokens = raw.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return LspCapabilities.ALL
        return LspCapabilities(
            completion = "completion" in tokens,
            hover = "hover" in tokens,
            definition = "definition" in tokens,
            references = "references" in tokens,
            documentSymbol = "symbols" in tokens || "documentsymbol" in tokens,
            formatting = "formatting" in tokens
        )
    }
}
