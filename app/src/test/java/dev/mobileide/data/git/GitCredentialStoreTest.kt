package dev.mobileide.data.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitCredentialStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stores and removes an HTTPS credential`() {
        val file = File(temporaryFolder.newFolder("git"), "credentials.dat")
        val store = GitCredentialStore(file, FakeCrypto())

        store.saveHttps("github.com", "alice", "secret-token")

        val credential = store.loadHttps("github.com")!!
        assertEquals("alice", credential.username)
        assertEquals("secret-token", credential.token)
        assertTrue(store.hasHttps("github.com"))
        assertFalse(file.readText().contains("secret-token"))

        store.removeHttps("github.com")
        assertNull(store.loadHttps("github.com"))
    }

    @Test
    fun `drops a credential that cannot be decrypted`() {
        val file = File(temporaryFolder.newFolder("git"), "credentials.dat")
        val store = GitCredentialStore(file, object : CredentialCrypto {
            override fun encrypt(plaintext: String): String = "broken"
            override fun decrypt(ciphertext: String): String? = null
        })

        store.saveHttps("github.com", "alice", "secret-token")

        assertNull(store.loadHttps("github.com"))
    }

    @Test
    fun `stores and clears the SSH passphrase`() {
        val file = File(temporaryFolder.newFolder("git"), "credentials.dat")
        val store = GitCredentialStore(file, FakeCrypto())

        store.saveSshPassphrase("key-pass")
        assertEquals("key-pass", store.loadSshPassphrase())
        assertTrue(file.readText().contains("ssh.passphrase"))

        store.saveSshPassphrase(null)
        assertNull(store.loadSshPassphrase())

        store.saveHttps("github.com", "alice", "token")
        store.clearAll()
        assertFalse(file.exists())
    }

    private class FakeCrypto : CredentialCrypto {
        override fun encrypt(plaintext: String): String =
            java.util.Base64.getEncoder()
                .encodeToString(plaintext.toByteArray(Charsets.UTF_8))

        override fun decrypt(ciphertext: String): String? = runCatching {
            String(java.util.Base64.getDecoder().decode(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }
}
