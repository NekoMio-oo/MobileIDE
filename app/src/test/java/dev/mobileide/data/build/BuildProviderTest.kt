package dev.mobileide.data.build

import dev.mobileide.data.build.providers.CcBuildProvider
import dev.mobileide.data.build.providers.GoBuildProvider
import dev.mobileide.data.build.providers.JavaBuildProvider
import dev.mobileide.data.build.providers.RustBuildProvider
import dev.mobileide.data.build.providers.TypeScriptBuildProvider
import dev.mobileide.domain.build.BuildConfig
import dev.mobileide.domain.build.BuildContext
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.DiagnosticDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProviderTest {

    private fun ccContext(
        language: BuildLanguage = BuildLanguage.C,
        compiler: String = "gcc",
        mode: BuildMode = BuildMode.DEBUG,
        sources: List<String> = listOf("util.c", "main.c"),
        entry: String? = null,
        standard: String = "c11"
    ) = BuildContext(
        config = BuildConfig(
            language = language,
            mode = mode,
            compiler = compiler,
            standard = standard,
            entryFile = entry,
            outputName = "app",
            includeDirs = listOf("include"),
            libraryDirs = listOf("libs"),
            libraries = listOf("m", "pthread"),
            compileFlags = listOf("-Wall"),
            linkFlags = listOf("-s")
        ),
        sources = sources,
        outputPath = "build/debug/app"
    )

    @Test
    fun `c build generates one gcc invocation with every flag`() {
        val provider = CcBuildProvider(BuildLanguage.C)
        val context = ccContext()
        assertNull(provider.validate(context))

        val plan = provider.plan(context)
        val command = plan.steps.single().guestCommand

        assertEquals("gcc", command.first())
        assertTrue(command.contains("-std=c11"))
        assertTrue(command.contains("-O0"))
        assertTrue(command.contains("-g"))
        assertEquals("/workspace/build/debug/app", command[command.indexOf("-o") + 1])
        assertTrue(command.contains("/workspace/main.c"))
        assertTrue(command.contains("/workspace/util.c"))
        assertTrue(command.indexOf("/workspace/main.c") < command.indexOf("/workspace/util.c"))
        assertTrue(command.contains("-I") && command.contains("/workspace/include"))
        assertTrue(command.contains("-L") && command.contains("/workspace/libs"))
        assertTrue(command.contains("-lm") && command.contains("-lpthread"))
        assertTrue(command.contains("-Wall"))
        assertTrue(command.contains("-s"))
        assertEquals(DiagnosticDialect.CC, plan.dialect)
        assertEquals(listOf("build/debug/app"), plan.artifactCandidates)
    }

    @Test
    fun `entry file is compiled first and release drops debug flags`() {
        val plan = CcBuildProvider(BuildLanguage.CPP).plan(
            ccContext(
                language = BuildLanguage.CPP,
                compiler = "g++",
                mode = BuildMode.RELEASE,
                sources = listOf("b.cpp", "a.cpp"),
                entry = "b.cpp",
                standard = "c++17"
            )
        )
        val command = plan.steps.single().guestCommand

        assertEquals("g++", command.first())
        assertTrue(command.contains("-std=c++17"))
        assertTrue(command.contains("-O2"))
        assertTrue(command.contains("-DNDEBUG"))
        assertTrue(!command.contains("-g"))
        assertTrue(command.indexOf("/workspace/b.cpp") < command.indexOf("/workspace/a.cpp"))
    }

    @Test
    fun `missing compiler or bad output name is rejected`() {
        val provider = CcBuildProvider(BuildLanguage.C)
        assertNotNull(provider.validate(ccContext(compiler = "")))
        assertNotNull(provider.validate(ccContext(standard = "c23")))

        val badOutput = ccContext().let {
            it.copy(config = it.config.copy(outputName = "dir/app"))
        }
        assertNotNull(provider.validate(badOutput))
    }

    @Test
    fun `java compiles then packages a jar`() {
        val provider = JavaBuildProvider()
        val context = BuildContext(
            config = BuildConfig(
                language = BuildLanguage.JAVA,
                compiler = "javac",
                standard = "17",
                outputName = "demo",
                classpath = listOf("libs/a.jar")
            ),
            sources = listOf("src/App.java", "src/Util.java"),
            outputPath = "build/debug/app"
        )
        assertNull(provider.validate(context))

        val plan = provider.plan(context)
        assertEquals(2, plan.steps.size)
        val javac = plan.steps[0].guestCommand
        assertTrue(javac.contains("--release") && javac.contains("17"))
        assertTrue(javac.contains("-d") && javac.contains("/workspace/build/classes"))
        assertTrue(javac.contains("/workspace/libs/a.jar"))
        val jar = plan.steps[1].guestCommand
        assertTrue(jar.contains("--create"))
        assertTrue(jar.contains("/workspace/build/debug/app.jar"))
        assertEquals(listOf("build/debug/app.jar"), plan.artifactCandidates)
        assertEquals(DiagnosticDialect.JAVA, plan.dialect)
    }

    @Test
    fun `go requires a module and targets linux arm64`() {
        val provider = GoBuildProvider()
        val withoutModule = BuildContext(
            config = BuildConfig(language = BuildLanguage.GO, compiler = "go", outputName = "app"),
            sources = listOf("main.go"),
            outputPath = "build/debug/app"
        )
        assertNotNull(provider.validate(withoutModule))

        val context = withoutModule.copy(rootFiles = setOf("go.mod"))
        assertNull(provider.validate(context))
        val step = provider.plan(context).steps.single()
        assertEquals("linux", step.environment["GOOS"])
        assertEquals("arm64", step.environment["GOARCH"])
        assertTrue(step.guestCommand.contains("-o"))
    }

    @Test
    fun `rust requires a cargo manifest and honours release`() {
        val provider = RustBuildProvider()
        val context = BuildContext(
            config = BuildConfig(
                language = BuildLanguage.RUST,
                compiler = "cargo",
                mode = BuildMode.RELEASE
            ),
            sources = emptyList(),
            outputPath = "build/release/app",
            rootFiles = setOf("Cargo.toml")
        )
        assertNull(provider.validate(context))
        val step = provider.plan(context).steps.single()
        assertTrue(step.guestCommand.contains("--release"))
        assertEquals("/workspace/build/cargo", step.environment["CARGO_TARGET_DIR"])
        assertEquals(listOf("build/cargo/release"), provider.plan(context).artifactCandidates)
    }

    @Test
    fun `typescript uses tsconfig when present and explicit files otherwise`() {
        val provider = TypeScriptBuildProvider()
        val withConfig = BuildContext(
            config = BuildConfig(language = BuildLanguage.TYPESCRIPT, compiler = "tsc"),
            sources = emptyList(),
            outputPath = "build/debug/app",
            rootFiles = setOf("tsconfig.json")
        )
        assertNull(provider.validate(withConfig))
        val tsconfigCommand = provider.plan(withConfig).steps.single().guestCommand
        assertTrue(tsconfigCommand.contains("-p"))
        assertTrue(tsconfigCommand.contains("/workspace/build/js"))

        val explicit = withConfig.copy(
            config = withConfig.config.copy(target = "ES2020"),
            sources = listOf("src/index.ts"),
            rootFiles = emptySet()
        )
        val explicitCommand = provider.plan(explicit).steps.single().guestCommand
        assertTrue(explicitCommand.contains("/workspace/src/index.ts"))
        assertTrue(explicitCommand.contains("ES2020"))
        assertEquals(DiagnosticDialect.TYPESCRIPT, provider.plan(explicit).dialect)
    }
}
