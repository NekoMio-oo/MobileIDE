package dev.mobileide.data.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ArchiveExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `tar gz extraction keeps content and executable bit`() {
        val archive = temporaryFolder.newFile("tree.tar.gz")
        tarGz(archive) { tar ->
            addFile(tar, "bin/tool", "#!/bin/sh\necho hi\n", 0b111_101_101)
            addFile(tar, "etc/config.txt", "key=value\n", 0b110_100_100)
        }
        val target = temporaryFolder.newFolder("out")

        ArchiveExtractor.extract(archive, target)

        val tool = File(target, "bin/tool")
        assertEquals("#!/bin/sh\necho hi\n", tool.readText())
        assertTrue(tool.canExecute())
        assertEquals("key=value\n", File(target, "etc/config.txt").readText())
    }

    @Test
    fun `tar xz archives are supported`() {
        val archive = temporaryFolder.newFile("tree.tar.xz")
        XZCompressorOutputStream(FileOutputStream(archive)).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                addFile(tar, "usr/bin/python3.11", "binary", 0b111_101_101)
            }
        }
        val target = temporaryFolder.newFolder("out")

        ArchiveExtractor.extract(archive, target)

        assertTrue(File(target, "usr/bin/python3.11").isFile)
    }

    @Test
    fun `path traversal entries are ignored`() {
        val archive = temporaryFolder.newFile("evil.tar.gz")
        tarGz(archive) { tar ->
            addFile(tar, "../escaped.txt", "bad", 0b110_100_100)
            addFile(tar, "/absolute.txt", "bad", 0b110_100_100)
            addFile(tar, "safe.txt", "ok", 0b110_100_100)
        }
        val target = temporaryFolder.newFolder("out")

        ArchiveExtractor.extract(archive, target)

        assertEquals("ok", File(target, "safe.txt").readText())
        assertFalse(File(target.parentFile, "escaped.txt").exists())
        assertFalse(File("/absolute.txt").exists())
    }

    @Test
    fun `symlinks are recreated without being followed`() {
        val archive = temporaryFolder.newFile("links.tar.gz")
        tarGz(archive) { tar ->
            addFile(tar, "usr/bin/tool", "x", 0b111_101_101)
            val link = TarArchiveEntry("usr/bin/tool-link", TarConstants.LF_SYMLINK)
            link.linkName = "tool"
            tar.putArchiveEntry(link)
            tar.closeArchiveEntry()
        }
        val target = temporaryFolder.newFolder("out")

        ArchiveExtractor.extract(archive, target)

        val link = File(target, "usr/bin/tool-link")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("tool", Files.readSymbolicLink(link.toPath()).toString())
        assertEquals("x", link.readText())
    }

    @Test
    fun `zip archives are supported`() {
        val archive = temporaryFolder.newFile("tree.zip")
        ZipArchiveOutputStream(archive).use { zip ->
            val entry = ZipArchiveEntry("dir/file.txt")
            zip.putArchiveEntry(entry)
            zip.write("zip content".toByteArray())
            zip.closeArchiveEntry()
        }
        val target = temporaryFolder.newFolder("out")

        ArchiveExtractor.extract(archive, target)

        assertEquals("zip content", File(target, "dir/file.txt").readText())
    }

    private fun tarGz(file: File, block: (TarArchiveOutputStream) -> Unit) {
        GzipCompressorOutputStream(FileOutputStream(file)).use { gzip ->
            TarArchiveOutputStream(gzip).use(block)
        }
    }

    private fun addFile(
        tar: TarArchiveOutputStream,
        name: String,
        content: String,
        mode: Int
    ) {
        val bytes = content.toByteArray()
        val entry = TarArchiveEntry(name)
        entry.size = bytes.size.toLong()
        entry.mode = mode
        tar.putArchiveEntry(entry)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }
}
