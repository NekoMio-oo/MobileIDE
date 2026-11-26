package dev.mobileide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.mobileide.core.designsystem.theme.MobileIdeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val darkTheme = isSystemInDarkTheme()

            // The activity handles uiMode changes itself (so open editors keep
            // their content), which means the system bar appearance must be
            // refreshed whenever the theme flips.
            LaunchedEffect(darkTheme) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            MobileIdeTheme(darkTheme = darkTheme) {
                MobileIdeApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as MobileIdeApplication).container
        if (container.allFilesAccess.hasAccess()) {
            lifecycleScope.launch {
                container.projectRootStore.ensureRoot()
            }
        }
    }
}
