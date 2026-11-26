package dev.mobileide.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import dev.mobileide.R
import java.util.Base64

/** Destinations shown in the bottom navigation bar. */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int
) {
    HOME("home", R.string.nav_home, R.drawable.ic_home),
    PROJECTS("projects", R.string.nav_projects, R.drawable.ic_folder),
    GIT("git", R.string.nav_git, R.drawable.ic_git),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_tune)
}

object Routes {
    const val CREATE_PROJECT = "create_project"
    const val GIT_PROJECT = "git/{projectId}"
    const val WORKSPACE = "workspace/{projectId}?dir={dir}"
    const val EDITOR = "editor/{projectId}?path={path}"
    const val RUNTIMES = "runtimes"
    const val LSP = "lsp"
    const val BUILD = "build/{projectId}"
    const val BUILD_CONFIG = "build/{projectId}/config"
    const val TERMINAL = "terminal/{projectId}"

    /**
     * [directory] is an optional project-relative folder to open directly, used
     * when jumping from a build artifact to its folder.
     */
    fun workspace(projectId: Long, directory: String? = null): String {
        if (directory.isNullOrBlank()) return "workspace/$projectId"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(directory.toByteArray(Charsets.UTF_8))
        return "workspace/$projectId?dir=$encoded"
    }

    fun gitProject(projectId: Long): String = "git/$projectId"

    fun build(projectId: Long): String = "build/$projectId"

    fun buildConfig(projectId: Long): String = "build/$projectId/config"

    fun terminal(projectId: Long): String = "terminal/$projectId"

    /**
     * Paths may contain slashes, spaces and non-ASCII characters. Base64 keeps
     * the route unambiguous and independent of Navigation's URI decoding.
     */
    fun editor(projectId: Long, path: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toByteArray(Charsets.UTF_8))
        return "editor/$projectId?path=$encoded"
    }
}

fun String?.isTopLevelRoute(): Boolean = TopLevelDestination.entries.any { it.route == this }

/**
 * Switches tabs while keeping each tab's own back stack, so returning to a tab
 * restores its scroll position and any nested screen.
 */
fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
