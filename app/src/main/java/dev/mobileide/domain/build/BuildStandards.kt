package dev.mobileide.domain.build

/**
 * Standards offered by the toolchains this app can install. These are the ones
 * the Debian 12 toolchain actually accepts (gcc 12 / clang 14 / OpenJDK 17 /
 * tsc 4); the UI never shows an option outside this list.
 */
object BuildStandards {

    val C = listOf("c99", "c11", "c17")
    val CPP = listOf("c++11", "c++14", "c++17", "c++20")
    val JAVA = listOf("8", "11", "17")
    val TYPESCRIPT = listOf("ES2017", "ES2020", "ES2022")

    fun forLanguage(language: BuildLanguage): List<String> = when (language) {
        BuildLanguage.C -> C
        BuildLanguage.CPP -> CPP
        BuildLanguage.JAVA -> JAVA
        BuildLanguage.TYPESCRIPT -> TYPESCRIPT
        else -> emptyList()
    }

    fun defaultFor(language: BuildLanguage): String = when (language) {
        BuildLanguage.C -> "c11"
        BuildLanguage.CPP -> "c++17"
        BuildLanguage.JAVA -> "17"
        BuildLanguage.TYPESCRIPT -> "ES2020"
        else -> ""
    }
}
