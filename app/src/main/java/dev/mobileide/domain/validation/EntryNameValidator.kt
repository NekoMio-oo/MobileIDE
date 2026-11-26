package dev.mobileide.domain.validation

enum class EntryNameError {
    EMPTY,
    TOO_LONG,
    INVALID_CHARACTERS,
    RESERVED
}

/**
 * Name rules for files and folders inside a project. Unlike project names,
 * leading dots are allowed because hidden entries such as `.gitignore` and
 * `.env` are legitimate project files.
 */
object EntryNameValidator {

    const val MAX_LENGTH = 128

    /** Filesystems cap a single name at 255 bytes even when it has fewer chars. */
    private const val MAX_BYTES = 255

    private val invalidCharacters = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun normalize(raw: String): String = raw.trim()

    fun validate(raw: String): EntryNameError? {
        val name = normalize(raw)
        return when {
            name.isEmpty() -> EntryNameError.EMPTY
            name.length > MAX_LENGTH -> EntryNameError.TOO_LONG
            name.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> EntryNameError.TOO_LONG
            name == "." || name == ".." -> EntryNameError.RESERVED
            name.any { it in invalidCharacters || it.isISOControl() } -> EntryNameError.INVALID_CHARACTERS
            name.endsWith(".") -> EntryNameError.INVALID_CHARACTERS
            else -> null
        }
    }
}
