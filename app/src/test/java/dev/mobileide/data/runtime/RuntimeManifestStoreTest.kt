package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.InstalledRuntimeRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeManifestStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `records round trip and can be removed`() = runTest {
        val file = File(temporaryFolder.newFolder("config"), "installed.properties")
        val store = RuntimeManifestStore(file)
        val record = InstalledRuntimeRecord(
            id = "LINUX",
            version = "12",
            installedAt = 1_700_000_000_000L,
            sha256 = "b".repeat(64),
            sizeBytes = 123_456L,
            path = "/data/user/0/dev.mobileide/files/runtime/linux"
        )

        store.put(record)
        assertEquals(record, store.record("LINUX"))

        store.remove("LINUX")
        assertNull(store.record("LINUX"))
    }

    @Test
    fun `unknown ids and corrupt files return nothing`() = runTest {
        val directory = temporaryFolder.newFolder("config")
        val file = File(directory, "installed.properties")
        val store = RuntimeManifestStore(file)

        assertNull(store.record("PYTHON"))

        file.writeText("LINUX=not-a-valid-record\n")
        assertEquals(emptyMap<String, InstalledRuntimeRecord>(), store.read())
    }
}
