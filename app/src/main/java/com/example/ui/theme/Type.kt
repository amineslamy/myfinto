package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import com.example.R
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val EstedadFontFamily = FontFamily(Font(R.font.estedad_light, FontWeight.Light))

private fun TextStyle.withEstedad() = copy(fontFamily = EstedadFontFamily)

val Typography = Typography().let { defaults ->
  defaults.copy(
    displayLarge = defaults.displayLarge.withEstedad(),
    displayMedium = defaults.displayMedium.withEstedad(),
    displaySmall = defaults.displaySmall.withEstedad(),
    headlineLarge = defaults.headlineLarge.withEstedad(),
    headlineMedium = defaults.headlineMedium.withEstedad(),
    headlineSmall = defaults.headlineSmall.withEstedad(),
    titleLarge = defaults.titleLarge.withEstedad(),
    titleMedium = defaults.titleMedium.withEstedad(),
    titleSmall = defaults.titleSmall.withEstedad(),
    bodyLarge = TextStyle(
      fontFamily = EstedadFontFamily,
      fontWeight = FontWeight.Normal,
      fontSize = 16.sp,
      lineHeight = 24.sp,
      letterSpacing = 0.5.sp,
    ),
    bodyMedium = defaults.bodyMedium.withEstedad(),
    bodySmall = defaults.bodySmall.withEstedad(),
    labelLarge = defaults.labelLarge.withEstedad(),
    labelMedium = defaults.labelMedium.withEstedad(),
    labelSmall = defaults.labelSmall.withEstedad(),
  )
}
