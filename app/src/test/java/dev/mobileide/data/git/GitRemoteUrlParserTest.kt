package dev.mobileide.data.git

import dev.mobileide.domain.git.GitRemoteAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitRemoteUrlParserTest {

    @Test
    fun `extracts embedded HTTPS credentials and cleans the URL`() {
        val parsed = GitRemoteUrlParser.parse(
            "https://alice:token123@github.com/owner/repo.git"
        )

        assertEquals(GitRemoteAuth.HTTPS, parsed.auth)
        assertEquals("github.com", parsed.host)
        assertEquals("github.com", parsed.hostKey)
        assertEquals("alice", parsed.username)
        assertEquals("token123", parsed.password)
        assertEquals("https://github.com/owner/repo.git", parsed.cleanUrl)
    }

    @Test
    fun `keeps a plain HTTPS URL unchanged`() {
        val parsed = GitRemoteUrlParser.parse("https://gitlab.com/group/project.git")

        assertEquals(GitRemoteAuth.HTTPS, parsed.auth)
        assertNull(parsed.username)
        assertNull(parsed.password)
        assertEquals("https://gitlab.com/group/project.git", parsed.cleanUrl)
    }

    @Test
    fun `recognizes SCP style SSH addresses`() {
        val parsed = GitRemoteUrlParser.parse("git@github.com:owner/repo.git")

        assertEquals(GitRemoteAuth.SSH, parsed.auth)
        assertEquals("github.com", parsed.host)
        assertEquals("git", parsed.username)
        assertNull(parsed.password)
        assertTrue(parsed.cleanUrl.startsWith("git@github.com:"))
    }

    @Test
    fun `includes an explicit port in the credential host key`() {
        val parsed = GitRemoteUrlParser.parse(
            "https://alice:secret@example.com:8443/team/repo.git"
        )

        assertEquals("example.com:8443", parsed.hostKey)
        assertEquals("https://example.com:8443/team/repo.git", parsed.cleanUrl)

        val ssh = GitRemoteUrlParser.parse("ssh://git@example.com:2222/repo.git")
        assertEquals(GitRemoteAuth.SSH, ssh.auth)
        assertEquals("example.com:2222", ssh.hostKey)
        assertEquals("git", ssh.username)
    }

    @Test
    fun `treats local paths as other remotes`() {
        val parsed = GitRemoteUrlParser.parse("/tmp/remote.git")

        assertEquals(GitRemoteAuth.OTHER, parsed.auth)
        assertNull(parsed.host)
        assertEquals("/tmp/remote.git", parsed.cleanUrl)
    }
}
