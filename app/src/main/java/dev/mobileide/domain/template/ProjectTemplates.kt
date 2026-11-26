package dev.mobileide.domain.template

import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.validation.ProjectNameValidator

/**
 * One entry of a project skeleton. [content] is null for directories, which
 * keeps the template order-independent: the writer creates directories first.
 */
data class TemplateEntry(
    val path: String,
    val content: String? = null
) {
    val isDirectory: Boolean get() = content == null
}

/**
 * Minimal, toolchain-free skeletons. They only need to be a sensible starting
 * point for the editor that lands in a later phase.
 */
object ProjectTemplates {

    fun forType(type: ProjectType, projectName: String): List<TemplateEntry> {
        val identifier = ProjectNameValidator.toIdentifier(projectName)
        return when (type) {
            ProjectType.EMPTY -> listOf(readme(projectName))

            ProjectType.C -> listOf(
                dir("src"),
                file("src/main.c", cMain(projectName)),
                dir("include"),
                readme(projectName)
            )

            ProjectType.CPP -> listOf(
                dir("src"),
                file("src/main.cpp", cppMain(projectName)),
                dir("include"),
                readme(projectName)
            )

            ProjectType.PYTHON -> listOf(
                file("main.py", pythonMain(projectName)),
                readme(projectName)
            )

            ProjectType.JAVA -> listOf(
                dir("src"),
                dir("src/main"),
                dir("src/main/java"),
                file("src/main/java/Main.java", javaMain(projectName)),
                readme(projectName)
            )

            ProjectType.KOTLIN -> listOf(
                dir("src"),
                dir("src/main"),
                dir("src/main/kotlin"),
                file("src/main/kotlin/Main.kt", kotlinMain(projectName)),
                readme(projectName)
            )

            ProjectType.JAVASCRIPT -> listOf(
                dir("src"),
                file("src/index.js", javaScriptMain()),
                readme(projectName)
            )

            ProjectType.TYPESCRIPT -> listOf(
                dir("src"),
                file("src/index.ts", typeScriptMain()),
                file("tsconfig.json", tsConfig()),
                readme(projectName)
            )

            ProjectType.GO -> listOf(
                file("main.go", goMain()),
                readme(projectName)
            )

            ProjectType.RUST -> listOf(
                dir("src"),
                file("src/main.rs", rustMain()),
                file("Cargo.toml", cargoToml(identifier)),
                readme(projectName)
            )

            ProjectType.WEB -> listOf(
                file("index.html", indexHtml(projectName)),
                file("style.css", styleCss()),
                file("script.js", scriptJs()),
                readme(projectName)
            )

            ProjectType.FOLDER -> listOf(readme(projectName))
        }
    }

    private fun dir(path: String) = TemplateEntry(path)

    private fun file(path: String, content: String) = TemplateEntry(path, content)

    private fun readme(projectName: String) = file(
        "README.md",
        "# $projectName\n\nCreated with MobileIDE.\n"
    )

    private fun cMain(projectName: String) = """
        /* $projectName */
        #include <stdio.h>

        int main(void) {
            printf("Hello, world!\n");
            return 0;
        }
    """.trimIndent() + "\n"

    private fun cppMain(projectName: String) = """
        /* $projectName */
        #include <iostream>

        int main() {
            std::cout << "Hello, world!" << std::endl;
            return 0;
        }
    """.trimIndent() + "\n"

    private fun pythonMain(projectName: String) = buildString {
        append("\"\"\"").append(projectName).append("\"\"\"\n\n")
        append("def main() -> None:\n")
        append("    print(\"Hello, world!\")\n\n\n")
        append("if __name__ == \"__main__\":\n")
        append("    main()\n")
    }

    private fun javaMain(projectName: String) = """
        /* $projectName */
        public class Main {
            public static void main(String[] args) {
                System.out.println("Hello, world!");
            }
        }
    """.trimIndent() + "\n"

    private fun kotlinMain(projectName: String) = """
        /* $projectName */
        fun main() {
            println("Hello, world!")
        }
    """.trimIndent() + "\n"

    private fun javaScriptMain() = """
        console.log("Hello, world!");
    """.trimIndent() + "\n"

    private fun typeScriptMain() = """
        const message: string = "Hello, world!";
        console.log(message);
    """.trimIndent() + "\n"

    private fun tsConfig() = """
        {
          "compilerOptions": {
            "target": "ES2020",
            "module": "ESNext",
            "strict": true,
            "outDir": "dist"
          },
          "include": ["src"]
        }
    """.trimIndent() + "\n"

    private fun goMain() = """
        package main

        import "fmt"

        func main() {
            fmt.Println("Hello, world!")
        }
    """.trimIndent() + "\n"

    private fun rustMain() = """
        fn main() {
            println!("Hello, world!");
        }
    """.trimIndent() + "\n"

    private fun cargoToml(identifier: String) = """
        [package]
        name = "$identifier"
        version = "0.1.0"
        edition = "2021"

        [dependencies]
    """.trimIndent() + "\n"

    private fun indexHtml(projectName: String) = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$projectName</title>
            <link rel="stylesheet" href="style.css">
        </head>
        <body>
            <main>
                <h1>$projectName</h1>
                <p>Created with MobileIDE.</p>
            </main>
            <script src="script.js"></script>
        </body>
        </html>
    """.trimIndent() + "\n"

    private fun styleCss() = """
        :root {
            color-scheme: light dark;
            font-family: system-ui, sans-serif;
        }

        body {
            margin: 0;
            min-height: 100vh;
            display: grid;
            place-items: center;
        }

        main {
            text-align: center;
        }
    """.trimIndent() + "\n"

    private fun scriptJs() = """
        console.log("Hello from MobileIDE");
    """.trimIndent() + "\n"
}
