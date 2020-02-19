package com.alamkanak.weekview

import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.M
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

@Suppress("DEPRECATION")
internal fun CharSequence.toTextLayout(
    textPaint: TextPaint,
    width: Int,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    spacingMultiplier: Float = 1f,
    spacingExtra: Float = 0f,
    includePad: Boolean = false
): StaticLayout = if (SDK_INT >= M) {
    StaticLayout.Builder
        .obtain(this, 0, this.length, textPaint, width)
        .setAlignment(alignment)
        .setLineSpacing(spacingExtra, spacingMultiplier)
        .setIncludePad(includePad)
        .build()
} else {
    StaticLayout(this, textPaint, width, alignment, spacingMultiplier, spacingExtra, includePad)
}
