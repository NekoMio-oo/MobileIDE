package dev.mobileide.domain

import dev.mobileide.domain.validation.EntryNameError
import dev.mobileide.domain.validation.EntryNameValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntryNameValidatorTest {

    @Test
    fun `ordinary and hidden file names are accepted`() {
        assertNull(EntryNameValidator.validate("main.c"))
        assertNull(EntryNameValidator.validate(".gitignore"))
        assertNull(EntryNameValidator.validate(".env"))
        assertNull(EntryNameValidator.validate("my folder"))
        assertNull(EntryNameValidator.validate("a.b.c.txt"))
    }

    @Test
    fun `blank names are rejected`() {
        assertEquals(EntryNameError.EMPTY, EntryNameValidator.validate("   "))
    }

    @Test
    fun `dot navigation names are reserved`() {
        assertEquals(EntryNameError.RESERVED, EntryNameValidator.validate("."))
        assertEquals(EntryNameError.RESERVED, EntryNameValidator.validate(".."))
    }

    @Test
    fun `path separators and control characters are rejected`() {
        assertEquals(
            EntryNameError.INVALID_CHARACTERS,
            EntryNameValidator.validate("a/b.txt")
        )
        assertEquals(
            EntryNameError.INVALID_CHARACTERS,
            EntryNameValidator.validate("a\\b.txt")
        )
        assertEquals(
            EntryNameError.INVALID_CHARACTERS,
            EntryNameValidator.validate("bad:name")
        )
        assertEquals(
            EntryNameError.INVALID_CHARACTERS,
            EntryNameValidator.validate("line\nbreak")
        )
    }

    @Test
    fun `names cannot end with a dot`() {
        assertEquals(
            EntryNameError.INVALID_CHARACTERS,
            EntryNameValidator.validate("name.")
        )
    }

    @Test
    fun `overlong names are rejected`() {
        val tooLong = "a".repeat(EntryNameValidator.MAX_LENGTH + 1)
        assertEquals(EntryNameError.TOO_LONG, EntryNameValidator.validate(tooLong))
    }

    @Test
    fun `normalize trims surrounding whitespace`() {
        assertEquals("notes.txt", EntryNameValidator.normalize("  notes.txt  "))
    }
}
