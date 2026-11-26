package dev.mobileide.data.runtime

import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class DebExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `deb data payload is extracted`() {
        val deb = temporaryFolder.newFile("proot.deb")
        val data = tarXz("data/data/com.termux/files/usr/bin/proot" to "binary")
        val control = tarXz()
        ArArchiveOutputStream(FileOutputStream(deb)).use { ar ->
            ar.putArchiveEntry(ArArchiveEntry("debian-binary", 4))
            ar.write("2.0\n".toByteArray())
            ar.closeArchiveEntry()
            ar.putArchiveEntry(ArArchiveEntry("control.tar.xz", control.size.toLong()))
            ar.write(control)
            ar.closeArchiveEntry()
            ar.putArchiveEntry(ArArchiveEntry("data.tar.xz", data.size.toLong()))
            ar.write(data)
            ar.closeArchiveEntry()
        }
        val target = temporaryFolder.newFolder("out")

        DebExtractor.extractData(deb, target)

        val extracted = File(target, "data/data/com.termux/files/usr/bin/proot")
        assertTrue(extracted.isFile)
        assertEquals("binary", extracted.readText())
    }

    @Test
    fun `deb without data payload is rejected`() {
        val deb = temporaryFolder.newFile("empty.deb")
        ArArchiveOutputStream(FileOutputStream(deb)).use { ar ->
            ar.putArchiveEntry(ArArchiveEntry("debian-binary", 4))
            ar.write("2.0\n".toByteArray())
            ar.closeArchiveEntry()
        }

        val failure = runCatching {
            DebExtractor.extractData(deb, temporaryFolder.newFolder("out"))
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
    }

    private fun tarXz(vararg files: Pair<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        XZCompressorOutputStream(buffer).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                files.forEach { (name, content) ->
                    val bytes = content.toByteArray()
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    entry.mode = 0b111_101_101
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return buffer.toByteArray()
    }
}
