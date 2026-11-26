package dev.mobileide.feature.build

import androidx.annotation.StringRes
import dev.mobileide.R
import dev.mobileide.domain.build.BuildState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


fun formatBuildDuration(millis: Long?): String {
    if (millis == null || millis < 0) return "—"
    val seconds = millis / 1000.0
    return if (seconds < 60) {
        String.format(Locale.US, "%.1fs", seconds)
    } else {
        val minutes = (seconds / 60).toInt()
        val rest = (seconds - minutes * 60).toInt()
        "${minutes}m ${rest}s"
    }
}

fun formatBuildTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return "—"
    // Built per call so a locale change is picked up without a static cache.
    return DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}

@StringRes
fun buildStateLabelRes(state: BuildState): Int = when (state) {
    BuildState.IDLE -> R.string.build_state_idle
    BuildState.PREPARING -> R.string.build_state_preparing
    BuildState.COMPILING -> R.string.build_state_compiling
    BuildState.SUCCEEDED -> R.string.build_state_succeeded
    BuildState.FAILED -> R.string.build_state_failed
    BuildState.CANCELLED -> R.string.build_state_cancelled
    BuildState.INTERRUPTED -> R.string.build_state_interrupted
}
