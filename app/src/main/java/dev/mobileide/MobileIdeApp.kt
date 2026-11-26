package dev.mobileide

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.mobileide.navigation.MobileIdeBottomBar
import dev.mobileide.navigation.MobileIdeNavHost
import dev.mobileide.navigation.isTopLevelRoute
import dev.mobileide.navigation.navigateToTopLevel
import kotlinx.coroutines.launch

/**
 * App shell: one Scaffold owns the Android system insets, the snackbar host and
 * the bottom bar, so screens never draw under the status or navigation bars.
 */
@Composable
fun MobileIdeApp() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showMessage: (String) -> Unit = remember(coroutineScope, snackbarHostState) {
        { message ->
            coroutineScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (currentRoute.isTopLevelRoute()) {
                MobileIdeBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { destination -> navController.navigateToTopLevel(destination) }
                )
            }
        }
    ) { innerPadding ->
        MobileIdeNavHost(
            navController = navController,
            onShowMessage = showMessage,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
