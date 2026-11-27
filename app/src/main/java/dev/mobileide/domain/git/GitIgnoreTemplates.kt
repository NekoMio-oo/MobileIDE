package dev.mobileide.domain.git

import dev.mobileide.domain.model.ProjectType

/** Ready-made .gitignore content for the languages MobileIDE creates. */
enum class GitIgnoreTemplate(val displayName: String, val content: String) {
    C_CPP(
        "C / C++",
        """
        # Build output
        build/
        cmake-build-*/
        *.o
        *.a
        *.so
        *.dylib
        *.d
        *.out
        *.exe
        """.trimIndent()
    ),
    PYTHON(
        "Python",
        """
        __pycache__/
        *.py[cod]
        *.egg-info/
        .venv/
        venv/
        .pytest_cache/
        .mypy_cache/
        dist/
        build/
        """.trimIndent()
    ),
    JAVA(
        "Java",
        """
        *.class
        build/
        out/
        target/
        .gradle/
        .idea/
        """.trimIndent()
    ),
    KOTLIN(
        "Kotlin",
        """
        *.class
        build/
        out/
        .gradle/
        .idea/
        """.trimIndent()
    ),
    JAVASCRIPT(
        "JavaScript",
        """
        node_modules/
        dist/
        build/
        coverage/
        .env
        *.log
        """.trimIndent()
    ),
    TYPESCRIPT(
        "TypeScript",
        """
        node_modules/
        dist/
        build/
        coverage/
        *.tsbuildinfo
        .env
        *.log
        """.trimIndent()
    ),
    GO(
        "Go",
        """
        bin/
        dist/
        vendor/
        *.exe
        *.test
        *.out
        """.trimIndent()
    ),
    RUST(
        "Rust",
        """
        /target/
        **/*.rs.bk
        *.pdb
        """.trimIndent()
    );

    val contentWithNewline: String
        get() = content.trimEnd() + "\n"
}

object GitIgnoreTemplates {

    val common: String = """
        # MobileIDE
        .mobileide/
        .DS_Store
        Thumbs.db
        *.log
        .env
    """.trimIndent() + "\n"

    fun forProjectType(type: ProjectType): String {
        val template = when (type) {
            ProjectType.C, ProjectType.CPP -> GitIgnoreTemplate.C_CPP
            ProjectType.PYTHON -> GitIgnoreTemplate.PYTHON
            ProjectType.JAVA -> GitIgnoreTemplate.JAVA
            ProjectType.KOTLIN -> GitIgnoreTemplate.KOTLIN
            ProjectType.JAVASCRIPT -> GitIgnoreTemplate.JAVASCRIPT
            ProjectType.TYPESCRIPT -> GitIgnoreTemplate.TYPESCRIPT
            ProjectType.GO -> GitIgnoreTemplate.GO
            ProjectType.RUST -> GitIgnoreTemplate.RUST
            ProjectType.EMPTY, ProjectType.WEB, ProjectType.FOLDER -> null
        }
        return buildString {
            append(common)
            if (template != null) {
                append("\n")
                append(template.contentWithNewline)
            }
        }
    }
}
