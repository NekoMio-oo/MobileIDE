package dev.mobileide.domain.editor

/**
 * Temporary storage for open tabs and unsaved buffers so a tab session can
 * survive process death. Deliberately not Room: these are transient buffers,
 * not project data.
 */
interface EditorSessionStore {

    suspend fun load(projectId: Long): PersistedEditorSession?

    suspend fun save(session: PersistedEditorSession)

    suspend fun delete(projectId: Long)
}
