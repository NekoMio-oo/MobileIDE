package dev.mobileide.core.storage

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Permission surface the repository depends on, fake-able in JVM tests. */
fun interface StorageAccess {
    fun hasAccess(): Boolean
}

/**
 * "All files access" for shared storage. API 30+ uses the special permission
 * (only granted from Settings); API 26-29 falls back to WRITE_EXTERNAL_STORAGE.
 */
class AllFilesAccess(private val context: Context) : StorageAccess {

    override fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True on API 26-29, where a runtime storage permission is used instead. */
    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    // Compile-time string constants; callers only open Settings on API 30+.
    @SuppressLint("InlinedApi")
    fun settingsIntent(): Intent {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        return if (appIntent.resolveActivity(context.packageManager) != null) {
            appIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    @Suppress("DEPRECATION")
    fun externalStorageRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}
