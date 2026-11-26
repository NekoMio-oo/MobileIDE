package dev.mobileide.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.mobileide.feature.build.BuildConfigScreen
import dev.mobileide.feature.build.BuildScreen
import dev.mobileide.feature.createproject.CreateProjectScreen
import dev.mobileide.feature.editor.EditorScreen
import dev.mobileide.feature.fileexplorer.FileExplorerScreen
import dev.mobileide.feature.git.GitScreen
import dev.mobileide.feature.home.HomeScreen
import dev.mobileide.feature.projects.ProjectsScreen
import dev.mobileide.feature.lsp.LspSettingsScreen
import dev.mobileide.feature.runtimes.RuntimesScreen
import dev.mobileide.feature.settings.SettingsScreen
import dev.mobileide.feature.terminal.TerminalScreen

@Composable
fun MobileIdeNavHost(
    navController: NavHostController,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.HOME.route,
        modifier = modifier
    ) {
        composable(TopLevelDestination.HOME.route) {
            HomeScreen(
                onNewProject = { navController.navigate(Routes.CREATE_PROJECT) },
                onOpenWorkspace = { projectId ->
                    navController.navigate(Routes.workspace(projectId))
                },
                onViewAllProjects = {
                    navController.navigateToTopLevel(TopLevelDestination.PROJECTS)
                },
                onOpenSettings = {
                    navController.navigateToTopLevel(TopLevelDestination.SETTINGS)
                },
                onShowMessage = onShowMessage
            )
        }

        composable(TopLevelDestination.PROJECTS.route) {
            ProjectsScreen(
                onShowMessage = onShowMessage,
                onOpenWorkspace = { projectId ->
                    navController.navigate(Routes.workspace(projectId))
                }
            )
        }

        composable(TopLevelDestination.GIT.route) {
            GitScreen()
        }

        composable(TopLevelDestination.SETTINGS.route) {
            SettingsScreen(
                onOpenRuntimes = { navController.navigate(Routes.RUNTIMES) },
                onOpenLsp = { navController.navigate(Routes.LSP) }
            )
        }

        composable(Routes.LSP) {
            LspSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenRuntimes = { navController.navigate(Routes.RUNTIMES) }
            )
        }

        composable(Routes.RUNTIMES) {
            RuntimesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CREATE_PROJECT) {
            CreateProjectScreen(
                onBack = { navController.popBackStack() },
                onCreated = { projectId ->
                    navController.navigate(Routes.workspace(projectId)) {
                        popUpTo(Routes.CREATE_PROJECT) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.WORKSPACE,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("dir") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId")?.toLongOrNull() ?: 0L
            FileExplorerScreen(
                onBack = { navController.popBackStack() },
                onOpenFile = { path -> navController.navigate(Routes.editor(projectId, path)) },
                onOpenBuild = { navController.navigate(Routes.build(projectId)) },
                onOpenTerminal = { navController.navigate(Routes.terminal(projectId)) }
            )
        }

        composable(
            route = Routes.BUILD,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId")?.toLongOrNull() ?: 0L
            BuildScreen(
                onBack = { navController.popBackStack() },
                onOpenConfig = { navController.navigate(Routes.buildConfig(projectId)) },
                onOpenEditor = { path -> navController.navigate(Routes.editor(projectId, path)) },
                onOpenArtifacts = { directory ->
                    navController.navigate(Routes.workspace(projectId, directory))
                },
                onOpenRuntimes = { navController.navigate(Routes.RUNTIMES) },
                onOpenTerminal = { navController.navigate(Routes.terminal(projectId)) }
            )
        }

        composable(
            route = Routes.BUILD_CONFIG,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) {
            BuildConfigScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType }
            )
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId")?.toLongOrNull() ?: 0L
            EditorScreen(
                onBack = { navController.popBackStack() },
                onOpenBuild = { navController.navigate(Routes.build(projectId)) },
                onOpenTerminal = { navController.navigate(Routes.terminal(projectId)) }
            )
        }

        composable(
            route = Routes.TERMINAL,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) {
            TerminalScreen(onBack = { navController.popBackStack() })
        }
    }
}
