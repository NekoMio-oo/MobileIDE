package dev.mobileide.data.terminal

import dev.mobileide.domain.terminal.TerminalEnvironment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TerminalPreferencesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing file yields defaults`() {
        val preferences = TerminalPreferences(File(temporaryFolder.root, "none.properties"))
        val settings = preferences.load()

        assertEquals(TerminalEnvironment.ANDROID, settings.defaultEnvironment)
        assertEquals(12, settings.fontSizeSp)
        assertEquals(3, settings.maxSessions)
    }

    @Test
    fun `save and load round trip`() {
        val file = File(temporaryFolder.root, "terminal.properties")
        val preferences = TerminalPreferences(file)

        preferences.save(
            TerminalSettings(
                defaultEnvironment = TerminalEnvironment.LINUX,
                fontSizeSp = 16,
                maxSessions = 2,
                scrollbackLines = 1_200
            )
        )
        val loaded = preferences.load()

        assertEquals(TerminalEnvironment.LINUX, loaded.defaultEnvironment)
        assertEquals(16, loaded.fontSizeSp)
        assertEquals(2, loaded.maxSessions)
        assertEquals(1_200, loaded.scrollbackLines)
    }

    @Test
    fun `values are clamped to usable ranges`() {
        val preferences = TerminalPreferences(File(temporaryFolder.root, "terminal.properties"))
        val saved = preferences.save(
            TerminalSettings(fontSizeSp = 99, maxSessions = 0, scrollbackLines = 5)
        )

        assertEquals(24, saved.fontSizeSp)
        assertEquals(1, saved.maxSessions)
        assertEquals(100, saved.scrollbackLines)
    }

    @Test
    fun `unknown environment falls back to android`() {
        val preferences = TerminalPreferences(File(temporaryFolder.root, "terminal.properties"))
        val settings = preferences.decode(
            preferences.encode(TerminalSettings()).replace("ANDROID", "SOMETHING_ELSE")
        )

        assertEquals(TerminalEnvironment.ANDROID, settings.defaultEnvironment)
    }
}
