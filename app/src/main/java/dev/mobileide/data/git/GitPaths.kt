package dev.mobileide.data.git

import java.io.File

/**
 * Application-private layout for Git support.
 *
 * files/git/               credentials, askpass helper and SSH material
 * files/git/ssh/           private keys, public keys and known_hosts
 * files/git/askpass/       fixed helper script (never contains a secret)
 * cache/git-temp/          pid files and temporary process data
 *
 * Nothing here lives inside a user project except the repository itself.
 */
class GitPaths(filesDir: File, cacheDir: File) {

    val gitDir = File(filesDir, "git")
    val sshDir = File(gitDir, "ssh")
    val askpassDir = File(gitDir, "askpass")
    val askpassScript = File(askpassDir, "askpass.sh")
    val credentialsFile = File(gitDir, "credentials.dat")
    val privateKeyFile = File(sshDir, "id_ed25519")
    val publicKeyFile = File(sshDir, "id_ed25519.pub")
    val knownHostsFile = File(sshDir, "known_hosts")
    val tempDir = File(cacheDir, "git-temp")

    fun ensureBaseDirs() {
        gitDir.mkdirs()
        sshDir.mkdirs()
        askpassDir.mkdirs()
        tempDir.mkdirs()
        ensureAskpassScript()
    }

    /**
     * The helper only forwards environment variables; it never contains a
     * token or passphrase. Git and ssh read the answers from this script.
     */
    private fun ensureAskpassScript() {
        if (askpassScript.isFile && askpassScript.readText() == ASKPASS_SCRIPT) return
        askpassScript.writeText(ASKPASS_SCRIPT)
        askpassScript.setReadable(false, false)
        askpassScript.setWritable(false, false)
        askpassScript.setExecutable(false, false)
        askpassScript.setReadable(true, true)
        askpassScript.setWritable(true, true)
        askpassScript.setExecutable(true, true)
    }

    private companion object {
        val ASKPASS_SCRIPT = """
            #!/bin/sh
            case "${'$'}1" in
              *Username*|*username*) printf '%s\n' "${'$'}MOBILEIDE_GIT_USERNAME" ;;
              *Password*|*password*) printf '%s\n' "${'$'}MOBILEIDE_GIT_TOKEN" ;;
              *[Pp]assphrase*) printf '%s\n' "${'$'}MOBILEIDE_SSH_PASSPHRASE" ;;
              *) printf '\n' ;;
            esac
        """.trimIndent() + "\n"
    }
}
