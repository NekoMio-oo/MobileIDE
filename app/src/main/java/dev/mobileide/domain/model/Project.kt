package dev.mobileide.domain.model

/** Project kinds offered by the create flow plus the fallback for opened folders. */
enum class ProjectType(val label: String, val displayName: String) {
    EMPTY("Empty", "Empty Project"),
    C("C", "C Project"),
    CPP("C++", "C++ Project"),
    PYTHON("Python", "Python Project"),
    JAVA("Java", "Java Project"),
    KOTLIN("Kotlin", "Kotlin Project"),
    JAVASCRIPT("JavaScript", "JavaScript Project"),
    TYPESCRIPT("TypeScript", "TypeScript Project"),
    GO("Go", "Go Project"),
    RUST("Rust", "Rust Project"),
    WEB("Web", "Web Project"),
    FOLDER("Folder", "Folder");

    companion object {
        val creatable: List<ProjectType> = entries.filterNot { it == FOLDER }

        fun fromStorage(value: String): ProjectType =
            entries.firstOrNull { it.name == value } ?: FOLDER
    }
}

/**
 * A project registered in MobileIDE. [path] is an absolute filesystem path;
 * null means the record was migrated from the SAF era and needs relocation.
 */
data class Project(
    val id: Long,
    val name: String,
    val type: ProjectType,
    val path: String?,
    val locationName: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val updatedAt: Long
) {
    val needsRelink: Boolean get() = path == null
}
