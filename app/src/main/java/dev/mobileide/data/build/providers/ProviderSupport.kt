package dev.mobileide.data.build.providers

import dev.mobileide.domain.build.BuildContext

/** Shared validation for providers that write a single output file. */
internal fun validateOutputName(name: String): String? {
    if (name.isBlank()) return "Output name is empty"
    if (name.contains('/') || name.contains('\\')) return "Output name must not contain a path separator"
    if (name == "." || name == "..") return "Invalid output name"
    if (name.length > 80) return "Output name is too long"
    return null
}

/**
 * Sources in a stable order with the configured entry file first, which is what
 * the linker needs when several files define helpers around one `main`.
 */
internal fun orderedSources(context: BuildContext): List<String> {
    val entry = context.config.entryFile
    val ordered = LinkedHashSet<String>()
    if (!entry.isNullOrBlank() && entry in context.sources) ordered += entry
    ordered += context.sources.sorted()
    return ordered.toList()
}
