package dev.mobileide.data.build

import dev.mobileide.domain.build.BuildLogLevel
import dev.mobileide.domain.build.BuildProblemSeverity
import dev.mobileide.domain.build.DiagnosticDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCollectorTest {

    private fun collect(dialect: DiagnosticDialect, vararg lines: String) =
        DiagnosticCollector(dialect).apply { lines.forEach { onLine(it) } }.problems

    @Test
    fun `clang errors carry file line and column`() {
        val problems = collect(
            DiagnosticDialect.CC,
            "src/main.c:12:5: error: use of undeclared identifier 'x'",
            "src/main.c:14:1: warning: unused variable 'y'"
        )

        assertEquals(2, problems.size)
        assertEquals("src/main.c", problems[0].path)
        assertEquals(12, problems[0].line)
        assertEquals(5, problems[0].column)
        assertEquals(BuildProblemSeverity.ERROR, problems[0].severity)
        assertEquals(BuildProblemSeverity.WARNING, problems[1].severity)
    }

    @Test
    fun `gcc without a column still parses`() {
        val problems = collect(DiagnosticDialect.CC, "main.c:7: error: expected ';'")
        assertEquals(1, problems.size)
        assertEquals(7, problems[0].line)
        assertNull(problems[0].column)
    }

    @Test
    fun `workspace prefix is stripped and outside paths stay unresolved`() {
        val problems = collect(
            DiagnosticDialect.CC,
            "/workspace/src/util.c:3:1: error: bad",
            "/usr/include/stdio.h:1:1: error: system header"
        )
        assertEquals("src/util.c", problems[0].path)
        assertNull(problems[1].path)
        assertEquals("/usr/include/stdio.h:1:1: error: system header", problems[1].raw)
    }

    @Test
    fun `javac diagnostics without column`() {
        val problems = collect(DiagnosticDialect.JAVA, "src/App.java:10: error: ';' expected")
        assertEquals("src/App.java", problems[0].path)
        assertEquals(10, problems[0].line)
        assertEquals(BuildProblemSeverity.ERROR, problems[0].severity)
    }

    @Test
    fun `go diagnostics include the column`() {
        val problems = collect(DiagnosticDialect.GO, "./main.go:8:2: undefined: foo")
        assertEquals("main.go", problems[0].path)
        assertEquals(8, problems[0].line)
        assertEquals(2, problems[0].column)
        assertEquals(BuildProblemSeverity.ERROR, problems[0].severity)
    }

    @Test
    fun `rust diagnostics combine the header with the arrow`() {
        val problems = collect(
            DiagnosticDialect.RUST,
            "error[E0425]: cannot find value `x` in this scope",
            " --> src/main.rs:2:5",
            "warning: unused variable: `y`",
            "  --> src/main.rs:9:9"
        )

        assertEquals(2, problems.size)
        assertEquals("src/main.rs", problems[0].path)
        assertEquals(2, problems[0].line)
        assertEquals(5, problems[0].column)
        assertEquals("cannot find value `x` in this scope", problems[0].message)
        assertEquals(BuildProblemSeverity.WARNING, problems[1].severity)
    }

    @Test
    fun `typescript diagnostics use parentheses format`() {
        val problems = collect(
            DiagnosticDialect.TYPESCRIPT,
            "src/app.ts(3,5): error TS2322: Type 'string' is not assignable to type 'number'."
        )
        assertEquals("src/app.ts", problems[0].path)
        assertEquals(3, problems[0].line)
        assertEquals(5, problems[0].column)
        assertTrue(problems[0].message.contains("not assignable"))
    }

    @Test
    fun `unrecognised lines are kept out of problems but classified for the log`() {
        val collector = DiagnosticCollector(DiagnosticDialect.CC)
        assertNull(collector.onLine("clang: error: linker command failed with exit code 1"))
        assertTrue(collector.problems.isEmpty())
        assertEquals(
            BuildLogLevel.ERROR,
            collector.levelOf("clang: error: linker command failed")
        )
        assertEquals(BuildLogLevel.WARNING, collector.levelOf("main.c:1:1: warning: unused"))
        assertEquals(BuildLogLevel.INFO, collector.levelOf("compiling 3 files"))
    }

    @Test
    fun `problem list is capped`() {
        val collector = DiagnosticCollector(DiagnosticDialect.CC, maxProblems = 5)
        repeat(20) { index ->
            collector.onLine("a.c:$index:1: error: problem $index")
        }
        assertEquals(5, collector.problems.size)
    }
}
