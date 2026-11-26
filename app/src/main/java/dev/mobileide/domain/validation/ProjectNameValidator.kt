package dev.mobileide.domain.validation

enum class ProjectNameError {
    EMPTY,
    TOO_LONG,
    INVALID_CHARACTERS,
    RESERVED
}

/**
 * Validation shared by creation and rename. The rules follow what shared-storage
 * filesystems accept as a folder name, so a valid name never needs escaping.
 */
object ProjectNameValidator {

    const val MAX_LENGTH = 64

    private val invalidCharacters = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun normalize(raw: String): String = raw.trim()

    fun validate(raw: String): ProjectNameError? {
        val name = normalize(raw)
        return when {
            name.isEmpty() -> ProjectNameError.EMPTY
            name.length > MAX_LENGTH -> ProjectNameError.TOO_LONG
            name == "." || name == ".." || name.startsWith(".") -> ProjectNameError.RESERVED
            name.any { it in invalidCharacters || it.isISOControl() } -> ProjectNameError.INVALID_CHARACTERS
            name.endsWith(".") || name.endsWith(" ") -> ProjectNameError.INVALID_CHARACTERS
            else -> null
        }
    }

    /** Turns a display name into a usable identifier for Cargo/TS metadata. */
    fun toIdentifier(raw: String): String {
        val identifier = normalize(raw)
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .trim('_')
        return identifier.ifEmpty { "project" }
    }
}
