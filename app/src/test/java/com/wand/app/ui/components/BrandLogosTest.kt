package com.wand.app.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BrandLogosTest {
    @Test
    fun multicolorTintRemainsUnspecifiedWhenApplyingAlpha() {
        assertEquals(
            Color.Unspecified,
            BrandLogos.tintWithAlpha(Color.Unspecified, alpha = 0.94f),
        )
    }

    @Test
    fun monochromeTintReceivesRequestedAlpha() {
        val source = Color(0xFF25D366)
        val tint = BrandLogos.tintWithAlpha(source, alpha = 0.72f)

        assertEquals(source.copy(alpha = 0.72f), tint)
    }
}
