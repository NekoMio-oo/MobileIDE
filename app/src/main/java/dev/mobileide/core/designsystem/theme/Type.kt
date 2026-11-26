package dev.mobileide.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * The stock Material 3 type scale with tighter display weights, which reads
 * closer to a desktop IDE without introducing a custom font.
 */
val MobileIdeTypography = Typography(
    headlineMedium = Default.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
        lineHeight = 34.sp
    ),
    titleLarge = Default.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = Default.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = Default.titleSmall.copy(
        fontWeight = FontWeight.Medium
    ),
    bodyMedium = Default.bodyMedium.copy(
        lineHeight = 20.sp
    ),
    bodySmall = Default.bodySmall.copy(
        lineHeight = 16.sp
    ),
    labelSmall = Default.labelSmall.copy(
        letterSpacing = 0.1.sp
    )
)
