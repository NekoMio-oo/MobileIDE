package dev.mobileide.data.build.providers

import dev.mobileide.domain.build.BuildContext
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildPlan
import dev.mobileide.domain.build.BuildProvider
import dev.mobileide.domain.build.DiagnosticDialect

/**
 * Languages without a build step inside this environment. The reason is shown
 * in the UI; no command is ever generated, so no fake success can appear.
 */
class UnsupportedBuildProvider(
    override val language: BuildLanguage,
    private val reason: String
) : BuildProvider {

    override fun validate(context: BuildContext): String = reason

    override fun plan(context: BuildContext): BuildPlan =
        BuildPlan(steps = emptyList(), artifactCandidates = emptyList(), dialect = DiagnosticDialect.NONE)

    companion object {
        const val KOTLIN_REASON =
            "Debian does not package the Kotlin compiler; building Kotlin needs a manual toolchain."
        const val SCRIPT_REASON =
            "This language has no build step. Run the script once the runtime supports it."

        fun reasonFor(language: BuildLanguage): String = when (language) {
            BuildLanguage.KOTLIN -> KOTLIN_REASON
            else -> SCRIPT_REASON
        }
    }
}
