package dev.mobileide.data.git

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

data class GitCredential(val username: String, val token: String)

/**
 * App-private credential store. HTTPS tokens and the SSH passphrase are
 * encrypted by [crypto]; usernames and host keys are not secret. The file is
 * never written into a project and its values are never logged.
 */
class GitCredentialStore(
    private val file: File,
    private val crypto: CredentialCrypto
) {

    private val lock = Any()

    fun saveHttps(host: String, username: String, token: String) {
        require(host.isNotBlank()) { "Host is required" }
        require(username.isNotBlank()) { "Username is required" }
        require(token.isNotBlank()) { "Token is required" }
        synchronized(lock) {
            val properties = load()
            val key = hostKey(host)
            properties.setProperty("https.$key.username", username)
            properties.setProperty("https.$key.token", crypto.encrypt(token))
            save(properties)
        }
    }

    fun loadHttps(host: String): GitCredential? = synchronized(lock) {
        val properties = load()
        val key = hostKey(host)
        val username = properties.getProperty("https.$key.username") ?: return null
        val encrypted = properties.getProperty("https.$key.token") ?: return null
        val token = crypto.decrypt(encrypted)
        if (token == null) {
            // Keystore key invalidation or a corrupted value: drop the entry.
            properties.remove("https.$key.username")
            properties.remove("https.$key.token")
            save(properties)
            return null
        }
        GitCredential(username, token)
    }

    fun hasHttps(host: String): Boolean = loadHttps(host) != null

    fun removeHttps(host: String) {
        synchronized(lock) {
            val properties = load()
            val key = hostKey(host)
            properties.remove("https.$key.username")
            properties.remove("https.$key.token")
            save(properties)
        }
    }

    fun saveSshPassphrase(passphrase: String?) {
        synchronized(lock) {
            val properties = load()
            if (passphrase.isNullOrBlank()) {
                properties.remove(SSH_PASSPHRASE)
            } else {
                properties.setProperty(SSH_PASSPHRASE, crypto.encrypt(passphrase))
            }
            save(properties)
        }
    }

    fun loadSshPassphrase(): String? = synchronized(lock) {
        val properties = load()
        val encrypted = properties.getProperty(SSH_PASSPHRASE) ?: return null
        val value = crypto.decrypt(encrypted)
        if (value == null) {
            properties.remove(SSH_PASSPHRASE)
            save(properties)
        }
        value
    }

    fun hasSshPassphrase(): Boolean = loadSshPassphrase() != null

    fun clearAll() {
        synchronized(lock) {
            file.delete()
        }
    }

    private fun load(): Properties {
        val properties = Properties()
        if (!file.isFile) return properties
        runCatching {
            file.inputStream().use { properties.load(it) }
        }
        return properties
    }

    private fun save(properties: Properties) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        try {
            temp.outputStream().use { properties.store(it, null) }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun hostKey(host: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(host.lowercase().toByteArray(Charsets.UTF_8))

    private companion object {
        const val SSH_PASSPHRASE = "ssh.passphrase"
    }
}
