package dev.mobileide.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Sha256Test {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `known content produces the expected digest`() {
        val file = File(temporaryFolder.newFolder(), "abc.txt")
        file.writeText("abc")

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.of(file)
        )
    }
}
