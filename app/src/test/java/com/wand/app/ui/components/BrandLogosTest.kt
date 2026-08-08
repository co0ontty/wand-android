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
    fun multicolorProviderTintPreservesBrandColors() {
        val tint = Color(0xFF25D366)

        assertEquals(Color.Unspecified, BrandLogos.tintForProvider("opencode", tint))
        assertEquals(Color.Unspecified, BrandLogos.tintForProvider("qoder", tint))
        assertEquals(tint, BrandLogos.tintForProvider("pi", tint))
    }

    @Test
    fun officialPiVectorCanBeConstructed() {
        assertEquals("BrandPi", BrandLogos.pi.name)
        assertEquals(469.43f, BrandLogos.pi.viewportWidth, 0.001f)
        assertEquals(469.43f, BrandLogos.pi.viewportHeight, 0.001f)
    }

    @Test
    fun squareBrandMarksReceiveSmallSizeOpticalCompensation() {
        assertEquals(17f / 15f, BrandLogos.opticalScale("opencode"), 0f)
        assertEquals(17f / 15f, BrandLogos.opticalScale("qoder"), 0f)
        assertEquals(1f, BrandLogos.opticalScale("pi"), 0f)
    }

    @Test
    fun monochromeTintReceivesRequestedAlpha() {
        val source = Color(0xFF25D366)
        val tint = BrandLogos.tintWithAlpha(source, alpha = 0.72f)

        assertEquals(source.copy(alpha = 0.72f), tint)
    }
}
