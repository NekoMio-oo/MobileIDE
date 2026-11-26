package dev.mobileide.data.editor

import dev.mobileide.domain.editor.EditorSessionStore
import dev.mobileide.domain.editor.PersistedEditorSession
import dev.mobileide.domain.editor.PersistedTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One binary file per project under the app's private directory.
 *
 * The file is written to a sibling temp file and atomically moved into place,
 * so a crash mid-write can never leave a half session behind. Any unreadable or
 * mismatched file is treated as "no session".
 */
class FileEditorSessionStore(
    private val directory: File
) : EditorSessionStore {

    override suspend fun load(projectId: Long): PersistedEditorSession? =
        withContext(Dispatchers.IO) {
            val file = fileFor(projectId)
            if (!file.isFile) return@withContext null
            try {
                DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                    if (input.readInt() != MAGIC) return@withContext null
                    if (input.readInt() != VERSION) return@withContext null
                    val storedProjectId = input.readLong()
                    if (storedProjectId != projectId) return@withContext null
                    val activePath = input.readUTF().ifEmpty { null }
                    val count = input.readInt()
                    if (count < 0 || count > MAX_TABS) return@withContext null

                    val tabs = ArrayList<PersistedTab>(count)
                    repeat(count) {
                        val relativePath = input.readUTF()
                        val name = input.readUTF()
                        val lineEnding = input.readUTF().ifEmpty { "\n" }
                        val cursorLine = input.readInt()
                        val cursorColumn = input.readInt()
                        val scrollY = input.readInt()
                        val baselineModified = input.readLong()
                        val baselineSize = input.readLong()
                        val dirty = input.readBoolean()
                        val missing = input.readBoolean()
                        val textLength = input.readInt()
                        val text = if (textLength >= 0) {
                            if (textLength > MAX_TEXT_BYTES) return@withContext null
                            val bytes = ByteArray(textLength)
                            input.readFully(bytes)
                            String(bytes, Charsets.UTF_8)
                        } else {
                            null
                        }
                        tabs += PersistedTab(
                            relativePath = relativePath,
                            name = name,
                            lineEnding = lineEnding,
                            cursorLine = cursorLine,
                            cursorColumn = cursorColumn,
                            scrollY = scrollY,
                            baselineModified = baselineModified,
                            baselineSize = baselineSize,
                            dirty = dirty,
                            missing = missing,
                            text = text
                        )
                    }
                    PersistedEditorSession(
                        projectId = projectId,
                        activeRelativePath = activePath,
                        tabs = tabs
                    )
                }
            } catch (error: Exception) {
                // Corrupt or partially written sessions must never block startup.
                null
            }
        }

    override suspend fun save(session: PersistedEditorSession) =
        withContext(Dispatchers.IO) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Could not create the editor session directory")
            }
            val target = fileFor(session.projectId)
            val temp = File.createTempFile("session-${session.projectId}-", ".tmp", directory)
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeLong(session.projectId)
                    output.writeUTF(session.activeRelativePath.orEmpty())
                    output.writeInt(session.tabs.size)
                    session.tabs.forEach { tab ->
                        output.writeUTF(tab.relativePath)
                        output.writeUTF(tab.name)
                        output.writeUTF(tab.lineEnding)
                        output.writeInt(tab.cursorLine)
                        output.writeInt(tab.cursorColumn)
                        output.writeInt(tab.scrollY)
                        output.writeLong(tab.baselineModified)
                        output.writeLong(tab.baselineSize)
                        output.writeBoolean(tab.dirty)
                        output.writeBoolean(tab.missing)
                        val bytes = tab.text?.toByteArray(Charsets.UTF_8)
                        output.writeInt(bytes?.size ?: -1)
                        if (bytes != null) output.write(bytes)
                    }
                }
                moveAtomically(temp, target)
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

    override suspend fun delete(projectId: Long) {
        withContext(Dispatchers.IO) { fileFor(projectId).delete() }
    }

    private fun fileFor(projectId: Long): File = File(directory, "project-$projectId.session")

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MAGIC = 0x4D494445 // "MIDE"
        const val VERSION = 1
        const val MAX_TABS = 64
        const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    }
}
