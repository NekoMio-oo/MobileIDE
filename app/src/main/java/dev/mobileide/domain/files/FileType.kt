package dev.mobileide.domain.files

import dev.mobileide.domain.editor.EditorLanguage

/**
 * How the UI groups a file. The icon family is derived from this, while
 * [FileType.language] drives syntax highlighting.
 */
enum class FileKind {
    CODE,
    MARKUP,
    DATA,
    TEXT,
    BINARY,
    UNKNOWN
}

/**
 * Single source of truth for extension based type detection. The editor's
 * [EditorLanguage] mapping delegates here, so the file list, the details sheet
 * and the editor can never disagree about a file's type.
 */
enum class FileType(
    val displayName: String,
    val extensions: Set<String>,
    val kind: FileKind,
    val language: EditorLanguage
) {
    C("C", setOf("c"), FileKind.CODE, EditorLanguage.C),
    C_HEADER("C/C++ Header", setOf("h"), FileKind.CODE, EditorLanguage.C),
    CPP("C++", setOf("cpp", "cc", "cxx"), FileKind.CODE, EditorLanguage.CPP),
    CPP_HEADER("C++ Header", setOf("hpp", "hxx"), FileKind.CODE, EditorLanguage.CPP),
    PYTHON("Python", setOf("py"), FileKind.CODE, EditorLanguage.PYTHON),
    JAVA("Java", setOf("java"), FileKind.CODE, EditorLanguage.JAVA),
    KOTLIN("Kotlin", setOf("kt"), FileKind.CODE, EditorLanguage.KOTLIN),
    KOTLIN_SCRIPT("Kotlin Script", setOf("kts"), FileKind.CODE, EditorLanguage.KOTLIN),
    JAVASCRIPT("JavaScript", setOf("js", "mjs", "cjs", "jsx"), FileKind.CODE, EditorLanguage.JAVASCRIPT),
    TYPESCRIPT("TypeScript", setOf("ts", "tsx"), FileKind.CODE, EditorLanguage.TYPESCRIPT),
    GO("Go", setOf("go"), FileKind.CODE, EditorLanguage.GO),
    RUST("Rust", setOf("rs"), FileKind.CODE, EditorLanguage.RUST),
    HTML("HTML", setOf("html", "htm"), FileKind.MARKUP, EditorLanguage.HTML),
    CSS("CSS", setOf("css"), FileKind.MARKUP, EditorLanguage.CSS),
    SVG("SVG", setOf("svg"), FileKind.MARKUP, EditorLanguage.PLAIN_TEXT),
    JSON("JSON", setOf("json"), FileKind.DATA, EditorLanguage.PLAIN_TEXT),
    XML("XML", setOf("xml"), FileKind.DATA, EditorLanguage.PLAIN_TEXT),
    YAML("YAML", setOf("yml", "yaml"), FileKind.DATA, EditorLanguage.PLAIN_TEXT),
    MARKDOWN("Markdown", setOf("md", "markdown"), FileKind.TEXT, EditorLanguage.PLAIN_TEXT),
    TEXT(
        "Text",
        setOf("txt", "log", "csv", "ini", "conf", "properties", "gradle", "sh"),
        FileKind.TEXT,
        EditorLanguage.PLAIN_TEXT
    ),
    IMAGE("Image", setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico"), FileKind.BINARY, EditorLanguage.PLAIN_TEXT),
    AUDIO("Audio", setOf("mp3", "wav", "ogg", "flac", "m4a", "aac"), FileKind.BINARY, EditorLanguage.PLAIN_TEXT),
    VIDEO("Video", setOf("mp4", "mkv", "mov", "webm", "avi", "3gp"), FileKind.BINARY, EditorLanguage.PLAIN_TEXT),
    ARCHIVE("Archive", setOf("zip", "tar", "gz", "rar", "7z", "apk", "jar"), FileKind.BINARY, EditorLanguage.PLAIN_TEXT),
    BINARY(
        "Binary file",
        setOf("so", "dex", "class", "bin", "ttf", "otf", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"),
        FileKind.BINARY,
        EditorLanguage.PLAIN_TEXT
    ),
    UNKNOWN("File", emptySet(), FileKind.UNKNOWN, EditorLanguage.PLAIN_TEXT);

    val isText: Boolean
        get() = kind != FileKind.BINARY

    companion object {

        fun fromFileName(fileName: String): FileType {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return UNKNOWN
            return entries.firstOrNull { extension in it.extensions } ?: UNKNOWN
        }

        /** True for entries the text editor must not open as UTF-8. */
        fun isProbablyBinary(fileName: String): Boolean =
            fromFileName(fileName).kind == FileKind.BINARY
    }
}
