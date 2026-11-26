package dev.mobileide.domain

import dev.mobileide.domain.model.ProjectType

/** Guesses the project kind of an imported folder from its top-level entries. */
object ProjectTypeDetector {

    fun detect(fileNames: List<String>): ProjectType {
        val names = fileNames.map { it.lowercase() }
        fun has(vararg candidates: String) = candidates.any { it in names }
        fun hasExtension(vararg extensions: String) =
            names.any { name -> extensions.any { name.endsWith(it) } }

        return when {
            has("cargo.toml") -> ProjectType.RUST
            has("go.mod") -> ProjectType.GO
            has("tsconfig.json") -> ProjectType.TYPESCRIPT
            has("package.json") -> ProjectType.JAVASCRIPT
            has("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") -> ProjectType.KOTLIN
            has("pom.xml") -> ProjectType.JAVA
            has("index.html") -> ProjectType.WEB
            hasExtension(".py") -> ProjectType.PYTHON
            hasExtension(".cpp", ".cc", ".cxx", ".hpp", ".hxx") -> ProjectType.CPP
            hasExtension(".c", ".h") -> ProjectType.C
            hasExtension(".java") -> ProjectType.JAVA
            hasExtension(".kt", ".kts") -> ProjectType.KOTLIN
            hasExtension(".ts", ".tsx") -> ProjectType.TYPESCRIPT
            hasExtension(".js", ".jsx", ".mjs") -> ProjectType.JAVASCRIPT
            hasExtension(".go") -> ProjectType.GO
            hasExtension(".rs") -> ProjectType.RUST
            hasExtension(".html", ".htm") -> ProjectType.WEB
            else -> ProjectType.FOLDER
        }
    }
}
