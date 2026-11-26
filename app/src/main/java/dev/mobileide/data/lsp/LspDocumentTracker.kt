package dev.mobileide.data.lsp

import dev.mobileide.domain.editor.EditorLanguage
import dev.mobileide.domain.lsp.LspDocumentKey

/** One document as the language server knows it. */
data class TrackedDocument(
    val projectId: Long,
    val projectPath: String,
    val relativePath: String,
    val language: EditorLanguage,
    val serverId: String,
    val languageId: String,
    val text: String,
    val version: Int
) {
    val uri: String
        get() = GuestUri.encode(relativePath)

    val key: LspDocumentKey
        get() = LspDocumentKey(projectId, relativePath)
}

/**
 * Version bookkeeping for open documents. Versions only move forward, and a
 * document is never announced twice without a `didClose` in between.
 */
class LspDocumentTracker(private val maxSyncChars: Int = 1_000_000) {

    data class Update(
        val document: TrackedDocument,
        /** Text changed and must be sent with `didChange`. */
        val changed: Boolean,
        /** The document is new for this server and needs `didOpen`. */
        val opened: Boolean,
        /** The text was too large to synchronize; the server keeps the old copy. */
        val skippedLarge: Boolean = false
    )

    private val documents = LinkedHashMap<LspDocumentKey, TrackedDocument>()

    @Synchronized
    fun open(
        projectId: Long,
        projectPath: String,
        relativePath: String,
        language: EditorLanguage,
        serverId: String,
        languageId: String,
        text: String
    ): Update {
        val key = LspDocumentKey(projectId, relativePath)
        val existing = documents[key]
        if (existing != null && existing.serverId == serverId) {
            if (existing.text == text) {
                return Update(existing, changed = false, opened = false)
            }
            if (!fits(text)) {
                return Update(existing, changed = false, opened = false, skippedLarge = true)
            }
            val updated = existing.copy(text = text, version = existing.version + 1)
            documents[key] = updated
            return Update(updated, changed = true, opened = false)
        }
        val document = TrackedDocument(
            projectId = projectId,
            projectPath = projectPath,
            relativePath = relativePath,
            language = language,
            serverId = serverId,
            languageId = languageId,
            text = if (fits(text)) text else "",
            version = 1
        )
        documents[key] = document
        return Update(document, changed = false, opened = true, skippedLarge = !fits(text))
    }

    @Synchronized
    fun change(key: LspDocumentKey, text: String): Update? {
        val existing = documents[key] ?: return null
        if (existing.text == text) return Update(existing, changed = false, opened = false)
        if (!fits(text)) {
            return Update(existing, changed = false, opened = false, skippedLarge = true)
        }
        val updated = existing.copy(text = text, version = existing.version + 1)
        documents[key] = updated
        return Update(updated, changed = true, opened = false)
    }

    @Synchronized
    fun close(key: LspDocumentKey): TrackedDocument? = documents.remove(key)

    @Synchronized
    fun get(key: LspDocumentKey): TrackedDocument? = documents[key]

    @Synchronized
    fun documentsFor(projectId: Long, serverId: String): List<TrackedDocument> =
        documents.values.filter { it.projectId == projectId && it.serverId == serverId }

    @Synchronized
    fun documentsForProject(projectId: Long): List<TrackedDocument> =
        documents.values.filter { it.projectId == projectId }

    @Synchronized
    fun removeProject(projectId: Long): List<TrackedDocument> {
        val removed = documentsForProject(projectId)
        removed.forEach { documents.remove(it.key) }
        return removed
    }

    @Synchronized
    fun count(): Int = documents.size

    @Synchronized
    fun allDocuments(): List<TrackedDocument> = documents.values.toList()

    private fun fits(text: String): Boolean = text.length <= maxSyncChars
}
