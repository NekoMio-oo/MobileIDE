package dev.mobileide.data.editor

import dev.mobileide.domain.editor.EditorLanguage
import dev.mobileide.domain.editor.EditorSessionStore
import dev.mobileide.domain.editor.EditorSnapshot
import dev.mobileide.domain.editor.EditorTab
import dev.mobileide.domain.editor.EditorTabBuffer
import dev.mobileide.domain.editor.EditorTabStatus
import dev.mobileide.domain.editor.EditorTabsState
import dev.mobileide.domain.editor.OpenTabResult
import dev.mobileide.domain.editor.PersistedEditorSession
import dev.mobileide.domain.editor.PersistedTab
import dev.mobileide.domain.repository.ProjectFileError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Source of truth for open tabs of every project.
 *
 * Tab identity is the real absolute path. Text buffers stay outside the state
 * flow (they can be megabytes) and are addressed by tab id. The manager owns no
 * coroutine scope: loading and saving go through the repository from the
 * ViewModel, and [persist] is called explicitly.
 */
class EditorTabManager(
    private val sessionStore: EditorSessionStore
) {

    private val states = mutableMapOf<Long, MutableStateFlow<EditorTabsState>>()
    private val buffers = mutableMapOf<String, EditorTabBuffer>()
    private val projectPaths = mutableMapOf<Long, String>()
    private val restoredProjects = mutableSetOf<Long>()
    private val storeMutex = Mutex()

    fun observe(projectId: Long): StateFlow<EditorTabsState> =
        stateOf(projectId).asStateFlow()

    fun tabs(projectId: Long): List<EditorTab> = stateOf(projectId).value.tabs

    fun activeTab(projectId: Long): EditorTab? = stateOf(projectId).value.activeTab

    fun buffer(tabId: String): EditorTabBuffer? = buffers[tabId]

    /**
     * Moves the caret of an open tab and bumps the revision so the editor
     * re-renders and scrolls the position into view. Used for build problems.
     */
    fun reveal(projectId: Long, tabId: String, line: Int, column: Int) {
        val state = stateOf(projectId)
        val buffer = buffers[tabId] ?: return
        val lineCount = buffer.text.count { it == '\n' } + 1
        val safeLine = line.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        val safeColumn = column.coerceAtLeast(0)
        buffers[tabId] = buffer.copy(cursorLine = safeLine, cursorColumn = safeColumn)
        state.value = state.value.copy(
            tabs = state.value.tabs.map { tab ->
                if (tab.id == tabId) {
                    tab.copy(cursorLine = safeLine, cursorColumn = safeColumn)
                } else {
                    tab
                }
            },
            revision = state.value.revision + 1
        )
    }

    /** Loads the persisted session once per project and editor root. */
    suspend fun restore(projectId: Long, projectPath: String) {
        val root = normalizeRoot(projectPath)
        if (restoredProjects.contains(projectId) && projectPaths[projectId] == root) return
        if (restoredProjects.contains(projectId)) {
            // The project folder moved: rebuild ids from the persisted relative paths.
            clearInMemory(projectId)
        }

        val session = storeMutex.withLock { sessionStore.load(projectId) }
        restoredProjects += projectId
        projectPaths[projectId] = root

        if (session == null) {
            emit(projectId, emptyList(), null, bump = true)
            return
        }

        val tabs = session.tabs.take(MAX_TABS).map { persisted ->
            val id = absoluteId(root, persisted.relativePath)
            if (persisted.text != null) {
                buffers[id] = EditorTabBuffer(
                    text = persisted.text,
                    cursorLine = persisted.cursorLine,
                    cursorColumn = persisted.cursorColumn,
                    scrollY = persisted.scrollY
                )
            }
            val status = when {
                persisted.missing -> EditorTabStatus.MISSING
                persisted.dirty && persisted.text != null -> EditorTabStatus.READY
                else -> EditorTabStatus.LOADING
            }
            EditorTab(
                id = id,
                projectId = projectId,
                relativePath = persisted.relativePath,
                name = persisted.name,
                language = EditorLanguage.fromFileName(persisted.name),
                lineEnding = persisted.lineEnding,
                status = status,
                isDirty = persisted.dirty,
                cursorLine = persisted.cursorLine,
                cursorColumn = persisted.cursorColumn,
                scrollY = persisted.scrollY,
                baselineModified = persisted.baselineModified,
                baselineSize = persisted.baselineSize,
                loadError = if (status == EditorTabStatus.MISSING) {
                    ProjectFileError.NOT_FOUND
                } else {
                    null
                }
            )
        }
        val activeId = session.activeRelativePath
            ?.let { absoluteId(root, it) }
            ?.takeIf { id -> tabs.any { it.id == id } }
            ?: tabs.firstOrNull()?.id
        emit(projectId, tabs, activeId, bump = true)
    }

    fun open(
        projectId: Long,
        projectPath: String,
        relativePath: String,
        name: String
    ): OpenTabResult {
        val root = normalizeRoot(projectPath)
        projectPaths[projectId] = root
        val id = absoluteId(root, relativePath)
        val state = stateOf(projectId)
        val existing = state.value.tabs.firstOrNull { it.id == id }
        if (existing != null) {
            if (state.value.activeTabId != id) {
                val tabs = syncBufferInto(state.value.tabs, id)
                state.value = state.value.copy(
                    tabs = tabs,
                    activeTabId = id,
                    revision = state.value.revision + 1
                )
            }
            return OpenTabResult.AlreadyOpen(id)
        }
        if (state.value.tabs.size >= MAX_TABS) return OpenTabResult.LimitReached

        val tab = EditorTab(
            id = id,
            projectId = projectId,
            relativePath = relativePath,
            name = name,
            language = EditorLanguage.fromFileName(name)
        )
        state.value = state.value.copy(
            tabs = state.value.tabs + tab,
            activeTabId = id,
            revision = state.value.revision + 1
        )
        restoredProjects += projectId
        return OpenTabResult.Created(id)
    }

    /** Activates [tabId] and stores the outgoing tab's editor snapshot first. */
    fun activate(projectId: Long, tabId: String, snapshot: EditorSnapshot?): Boolean {
        val state = stateOf(projectId)
        val previousActive = state.value.activeTabId
        if (snapshot != null && previousActive != null) {
            buffers[previousActive] = snapshot.toBuffer()
        }
        if (state.value.tabs.none { it.id == tabId }) return false
        if (previousActive == tabId) return false

        state.value = state.value.copy(
            tabs = syncBufferInto(state.value.tabs, tabId),
            activeTabId = tabId,
            revision = state.value.revision + 1
        )
        return true
    }

    /** Keeps the active tab's text current without emitting UI state. */
    fun updateActive(projectId: Long, snapshot: EditorSnapshot) {
        val activeId = stateOf(projectId).value.activeTabId ?: return
        buffers[activeId] = snapshot.toBuffer()
    }

    fun markDirty(projectId: Long, tabId: String) {
        val state = stateOf(projectId)
        val tab = state.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.isDirty) return
        state.value = state.value.copy(tabs = state.value.tabs.replace(tab.copy(isDirty = true)))
    }

    fun onLoaded(
        projectId: Long,
        tabId: String,
        text: String,
        lineEnding: String,
        lastModified: Long,
        size: Long
    ) {
        val state = stateOf(projectId)
        val tab = state.value.tabs.firstOrNull { it.id == tabId } ?: return
        buffers[tabId] = EditorTabBuffer(text, tab.cursorLine, tab.cursorColumn, tab.scrollY)
        val updated = tab.copy(
            status = EditorTabStatus.READY,
            isDirty = false,
            lineEnding = lineEnding,
            baselineModified = lastModified,
            baselineSize = size,
            loadError = null,
            loadErrorDetail = null
        )
        state.value = state.value.copy(
            tabs = state.value.tabs.replace(updated),
            revision = state.value.revision + 1
        )
    }

    fun onLoadFailed(
        projectId: Long,
        tabId: String,
        error: ProjectFileError,
        detail: String?
    ) {
        val state = stateOf(projectId)
        val tab = state.value.tabs.firstOrNull { it.id == tabId } ?: return
        state.value = state.value.copy(
            tabs = state.value.tabs.replace(
                tab.copy(
                    status = if (error == ProjectFileError.NOT_FOUND) {
                        EditorTabStatus.MISSING
                    } else {
                        EditorTabStatus.FAILED
                    },
                    loadError = error,
                    loadErrorDetail = detail
                )
            ),
            revision = state.value.revision + 1
        )
    }

    fun markSaved(
        projectId: Long,
        tabId: String,
        snapshot: EditorSnapshot,
        lastModified: Long,
        size: Long
    ) {
        val state = stateOf(projectId)
        val tab = state.value.tabs.firstOrNull { it.id == tabId } ?: return
        buffers[tabId] = snapshot.toBuffer()
        state.value = state.value.copy(
            tabs = state.value.tabs.replace(
                tab.copy(
                    status = EditorTabStatus.READY,
                    isDirty = false,
                    externallyModified = false,
                    baselineModified = lastModified,
                    baselineSize = size,
                    loadError = null,
                    loadErrorDetail = null
                )
            )
        )
    }

    /** Replaces a tab with text read from disk (external change / reload). */
    fun reload(
        projectId: Long,
        tabId: String,
        text: String,
        lineEnding: String,
        lastModified: Long,
        size: Long
    ) {
        val state = stateOf(projectId)
        val tab = state.value.tabs.firstOrNull { it.id == tabId } ?: return
        buffers[tabId] = EditorTabBuffer(text, tab.cursorLine, tab.cursorColumn, tab.scrollY)
        state.value = state.value.copy(
            tabs = state.value.tabs.replace(
                tab.copy(
                    status = EditorTabStatus.READY,
                    isDirty = false,
                    externallyModified = false,
                    lineEnding = lineEnding,
                    baselineModified = lastModified,
                    baselineSize = size,
                    loadError = null,
                    loadErrorDetail = null
                )
            ),
            revision = state.value.revision + 1
        )
    }

    fun markMissing(projectId: Long, tabId: String) {
        val state = stateOf(projectId)
        val tab = state.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.status == EditorTabStatus.MISSING) return
        state.value = state.value.copy(
            tabs = state.value.tabs.replace(
                tab.copy(
                    status = EditorTabStatus.MISSING,
                    loadError = ProjectFileError.NOT_FOUND,
                    loadErrorDetail = null
                )
            ),
            revision = state.value.revision + 1
        )
    }

    fun setExternallyModified(projectId: Long, tabId: String, changed: Boolean) {
        val state = stateOf(projectId)
        val tab = state.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.externallyModified == changed) return
        state.value = state.value.copy(
            tabs = state.value.tabs.replace(tab.copy(externallyModified = changed))
        )
    }

    fun close(projectId: Long, tabId: String): Boolean {
        val state = stateOf(projectId)
        val tabs = state.value.tabs
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return false
        val remaining = tabs.filterNot { it.id == tabId }
        buffers.remove(tabId)
        val nextActive = if (state.value.activeTabId == tabId) {
            remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.id
        } else {
            state.value.activeTabId
        }
        state.value = state.value.copy(
            tabs = remaining,
            activeTabId = nextActive,
            revision = state.value.revision + 1
        )
        return true
    }

    fun closeOthers(projectId: Long) {
        val state = stateOf(projectId)
        val activeId = state.value.activeTabId ?: return
        val kept = state.value.tabs.filter { it.id == activeId }
        state.value.tabs.filterNot { it.id == activeId }.forEach { buffers.remove(it.id) }
        state.value = state.value.copy(
            tabs = kept,
            activeTabId = activeId,
            revision = state.value.revision + 1
        )
    }

    fun closeAll(projectId: Long) {
        val state = stateOf(projectId)
        state.value.tabs.forEach { buffers.remove(it.id) }
        state.value = state.value.copy(
            tabs = emptyList(),
            activeTabId = null,
            revision = state.value.revision + 1
        )
    }

    /** Updates tab paths after a file or folder rename; returns true when tabs changed. */
    fun onEntryRenamed(
        projectId: Long,
        projectPath: String,
        oldRelativePath: String,
        newRelativePath: String
    ): Boolean {
        val state = stateOf(projectId)
        val root = normalizeRoot(projectPath)
        val affected = state.value.tabs.filter { tab ->
            tab.relativePath == oldRelativePath ||
                tab.relativePath.startsWith("$oldRelativePath/")
        }
        if (affected.isEmpty()) return false

        val previousActive = state.value.activeTabId
        var nextActive = previousActive
        val updatedTabs = state.value.tabs.map { tab ->
            if (affected.none { it.id == tab.id }) return@map tab
            val suffix = tab.relativePath.removePrefix(oldRelativePath)
            val newRelative = newRelativePath + suffix
            val newId = absoluteId(root, newRelative)
            buffers.remove(tab.id)?.let { buffers[newId] = it }
            if (previousActive == tab.id) nextActive = newId
            tab.copy(
                id = newId,
                relativePath = newRelative,
                name = newRelative.substringAfterLast('/'),
                language = EditorLanguage.fromFileName(newRelative)
            )
        }
        state.value = state.value.copy(
            tabs = updatedTabs,
            activeTabId = nextActive,
            revision = if (nextActive != previousActive) {
                state.value.revision + 1
            } else {
                state.value.revision
            }
        )
        return true
    }

    /** Marks tabs under a deleted path as missing; buffers are kept. */
    fun onEntryDeleted(projectId: Long, path: String): Boolean {
        val state = stateOf(projectId)
        val affected = state.value.tabs.filter { tab ->
            tab.relativePath == path || tab.relativePath.startsWith("$path/")
        }
        if (affected.isEmpty()) return false
        val activeAffected = affected.any { it.id == state.value.activeTabId }
        state.value = state.value.copy(
            tabs = state.value.tabs.map { tab ->
                if (affected.none { it.id == tab.id }) {
                    tab
                } else {
                    tab.copy(
                        status = EditorTabStatus.MISSING,
                        loadError = ProjectFileError.NOT_FOUND,
                        loadErrorDetail = null
                    )
                }
            },
            revision = state.value.revision + if (activeAffected) 1 else 0
        )
        return true
    }

    suspend fun onProjectRemoved(projectId: Long) {
        clearInMemory(projectId)
        restoredProjects -= projectId
        projectPaths.remove(projectId)
        stateOf(projectId).value = EditorTabsState(projectId = projectId)
        storeMutex.withLock { sessionStore.delete(projectId) }
    }

    /** Writes the tab session (metadata + dirty buffers) for process-death restore. */
    suspend fun persist(projectId: Long, activeSnapshot: EditorSnapshot? = null) {
        val state = stateOf(projectId).value
        if (state.tabs.isEmpty()) {
            storeMutex.withLock { sessionStore.delete(projectId) }
            return
        }
        if (activeSnapshot != null && state.activeTabId != null) {
            buffers[state.activeTabId] = activeSnapshot.toBuffer()
        }
        val activeRelative = state.activeTabId
            ?.let { id -> state.tabs.firstOrNull { it.id == id }?.relativePath }
        val persistedTabs = state.tabs.map { tab ->
            val buffer = buffers[tab.id]
            PersistedTab(
                relativePath = tab.relativePath,
                name = tab.name,
                lineEnding = tab.lineEnding,
                cursorLine = buffer?.cursorLine ?: tab.cursorLine,
                cursorColumn = buffer?.cursorColumn ?: tab.cursorColumn,
                scrollY = buffer?.scrollY ?: tab.scrollY,
                baselineModified = tab.baselineModified,
                baselineSize = tab.baselineSize,
                dirty = tab.isDirty && buffer != null,
                missing = tab.status == EditorTabStatus.MISSING,
                text = if (tab.isDirty) buffer?.text else null
            )
        }
        val session = PersistedEditorSession(
            projectId = projectId,
            activeRelativePath = activeRelative,
            tabs = persistedTabs
        )
        storeMutex.withLock { sessionStore.save(session) }
    }

    private fun stateOf(projectId: Long): MutableStateFlow<EditorTabsState> =
        states.getOrPut(projectId) { MutableStateFlow(EditorTabsState(projectId = projectId)) }

    private fun emit(
        projectId: Long,
        tabs: List<EditorTab>,
        activeTabId: String?,
        bump: Boolean
    ) {
        val state = stateOf(projectId)
        state.value = state.value.copy(
            projectId = projectId,
            tabs = tabs,
            activeTabId = activeTabId,
            revision = if (bump) state.value.revision + 1 else state.value.revision
        )
    }

    private fun syncBufferInto(tabs: List<EditorTab>, tabId: String): List<EditorTab> {
        val buffer = buffers[tabId] ?: return tabs
        return tabs.replace(
            tabs.firstOrNull { it.id == tabId }?.copy(
                cursorLine = buffer.cursorLine,
                cursorColumn = buffer.cursorColumn,
                scrollY = buffer.scrollY
            ) ?: return tabs
        )
    }

    private fun clearInMemory(projectId: Long) {
        val tabs = stateOf(projectId).value.tabs
        tabs.forEach { buffers.remove(it.id) }
        stateOf(projectId).value = EditorTabsState(projectId = projectId)
    }

    private fun EditorSnapshot.toBuffer(): EditorTabBuffer = EditorTabBuffer(
        text = text,
        cursorLine = cursorLine,
        cursorColumn = cursorColumn,
        scrollY = scrollY
    )

    private fun List<EditorTab>.replace(tab: EditorTab): List<EditorTab> =
        map { if (it.id == tab.id) tab else it }

    private fun normalizeRoot(projectPath: String): String =
        projectPath.trimEnd('/').ifEmpty { projectPath }

    private fun absoluteId(root: String, relativePath: String): String =
        "$root/${relativePath.trimStart('/')}"

    private companion object {
        const val MAX_TABS = 12
    }
}
