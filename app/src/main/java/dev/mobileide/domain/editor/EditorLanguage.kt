package dev.mobileide.domain.editor

import dev.mobileide.domain.files.FileType

/**
 * Languages the editor recognises. Extension mapping lives in [FileType] so the
 * file manager and the editor share one table.
 */
enum class EditorLanguage(val displayName: String) {
    C("C"),
    CPP("C++"),
    PYTHON("Python"),
    JAVA("Java"),
    KOTLIN("Kotlin"),
    JAVASCRIPT("JavaScript"),
    TYPESCRIPT("TypeScript"),
    GO("Go"),
    RUST("Rust"),
    HTML("HTML"),
    CSS("CSS"),
    PLAIN_TEXT("Plain text");

    companion object {
        fun fromFileName(fileName: String): EditorLanguage =
            FileType.fromFileName(fileName).language
    }
}
