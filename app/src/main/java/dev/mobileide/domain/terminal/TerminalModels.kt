package dev.mobileide.domain.terminal

/** Where a terminal session runs. */
enum class TerminalEnvironment(val displayName: String) {
    /** `/system/bin/sh` with the app's own UID. No bash, no GNU userland. */
    ANDROID("Android shell"),

    /** `/bin/bash` inside the installed Debian userland through PRoot. */
    LINUX("Debian (PRoot)")
}

data class TerminalSessionInfo(
    val id: String,
    val environment: TerminalEnvironment,
    val projectId: Long,
    val projectName: String,
    /** Directory shown in the UI (host path for Android, `/workspace` for Linux). */
    val workingDirectory: String,
    val isRunning: Boolean = true,
    val exitCode: Int? = null,
    val error: String? = null
)

data class TerminalUiState(
    val info: TerminalSessionInfo,
    val snapshot: TerminalSnapshot
)

/** Keys sent by the on-screen helper bar; each maps to the exact bytes a terminal expects. */
enum class TerminalKey {
    ESCAPE,
    TAB,
    ARROW_UP,
    ARROW_DOWN,
    ARROW_LEFT,
    ARROW_RIGHT,
    CTRL_C,
    CTRL_D,
    CTRL_L,
    CTRL_Z,
    CTRL_A,
    CTRL_E,
    CTRL_U,
    CTRL_K,
    SLASH,
    DASH,
    ENTER;

    fun bytes(): ByteArray = when (this) {
        ESCAPE -> byteArrayOf(0x1B)
        TAB -> byteArrayOf(0x09)
        ARROW_UP -> "\u001b[A".toByteArray()
        ARROW_DOWN -> "\u001b[B".toByteArray()
        ARROW_RIGHT -> "\u001b[C".toByteArray()
        ARROW_LEFT -> "\u001b[D".toByteArray()
        CTRL_C -> byteArrayOf(0x03)
        CTRL_D -> byteArrayOf(0x04)
        CTRL_L -> byteArrayOf(0x0C)
        CTRL_Z -> byteArrayOf(0x1A)
        CTRL_A -> byteArrayOf(0x01)
        CTRL_E -> byteArrayOf(0x05)
        CTRL_U -> byteArrayOf(0x15)
        CTRL_K -> byteArrayOf(0x0B)
        SLASH -> "/".toByteArray()
        DASH -> "-".toByteArray()
        ENTER -> byteArrayOf(0x0D)
    }
}
