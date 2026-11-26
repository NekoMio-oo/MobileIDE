package dev.mobileide.core.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Formats a past timestamp for list rows, e.g. "12 min ago" or "Yesterday".
 * Kept as a pure function so it can be unit tested without Android resources.
 */
fun formatRelativeTime(
    timestampMillis: Long,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val elapsedMinutes = (nowMillis - timestampMillis).coerceAtLeast(0L) / 60_000L
    return when {
        elapsedMinutes < 1L -> "Just now"
        elapsedMinutes < 60L -> "$elapsedMinutes min ago"
        elapsedMinutes < 24L * 60L -> {
            val hours = elapsedMinutes / 60L
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
        elapsedMinutes < 48L * 60L -> "Yesterday"
        elapsedMinutes < 7L * 24L * 60L -> "${elapsedMinutes / (24L * 60L)} days ago"
        else -> {
            val weeks = elapsedMinutes / (7L * 24L * 60L)
            if (weeks == 1L) "1 week ago" else "$weeks weeks ago"
        }
    }
}

fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
