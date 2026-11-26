package dev.mobileide.domain.lsp

import dev.mobileide.domain.editor.EditorLanguage

/** LSP `languageId` values for the editor's languages. */
object LspLanguageIds {

    fun of(language: EditorLanguage): String = when (language) {
        EditorLanguage.C -> "c"
        EditorLanguage.CPP -> "cpp"
        EditorLanguage.PYTHON -> "python"
        EditorLanguage.JAVA -> "java"
        EditorLanguage.KOTLIN -> "kotlin"
        EditorLanguage.JAVASCRIPT -> "javascript"
        EditorLanguage.TYPESCRIPT -> "typescript"
        EditorLanguage.GO -> "go"
        EditorLanguage.RUST -> "rust"
        EditorLanguage.HTML -> "html"
        EditorLanguage.CSS -> "css"
        EditorLanguage.PLAIN_TEXT -> "plaintext"
    }
}
