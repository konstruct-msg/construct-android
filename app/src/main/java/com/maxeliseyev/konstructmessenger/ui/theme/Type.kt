package com.maxeliseyev.konstructmessenger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val CTFontRegular = FontFamily.Monospace
val CTFontBold = FontFamily.Monospace

fun ctRegular(size: Int) = TextStyle(
    fontFamily = CTFontRegular,
    fontSize = size.sp,
    fontWeight = FontWeight.Normal
)

fun ctBold(size: Int) = TextStyle(
    fontFamily = CTFontBold,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold
)

val Typography = Typography(
    bodyLarge = ctRegular(16),
    bodyMedium = ctRegular(14),
    bodySmall = ctRegular(12),
    titleLarge = ctBold(22),
    titleMedium = ctBold(18),
    titleSmall = ctBold(14),
    labelLarge = ctRegular(14),
    labelMedium = ctRegular(12),
    labelSmall = ctRegular(10)
)