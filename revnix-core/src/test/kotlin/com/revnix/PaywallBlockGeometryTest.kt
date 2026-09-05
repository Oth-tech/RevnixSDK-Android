package com.revnix

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The geometry style fields — `clipPath`, `translate`, `fillSize`, `textWrap`.
 *
 * All four were named by revnix-app's BlockStyle and by NONE of the native
 * renderers, so [BlockStyle.from] dropped them. 13 of the 25 shipped template
 * categories set at least one. The two that change where pixels land are
 * pinned here: a polygon must parse (and be classified convex correctly, since
 * that is what decides whether Android can clip it at all), and a percentage
 * translate must resolve against the block's OWN size.
 *
 * The other two are pinned as CARRIED: they must survive decoding and merging
 * even though this platform cannot draw them, because a field that decodes is
 * a documented limitation while a field that does not is a silent one.
 */
class PaywallBlockGeometryTest {

    // ——— lengths ———

    @Test
    fun `length reads every form the designs write`() {
        assertEquals(RevnixLength(px = 16.0), revnixParseLength("16px"))
        assertEquals(RevnixLength(px = -8.0), revnixParseLength("-8px"))
        // A bare number is px — how the designs write a zero.
        assertEquals(RevnixLength(px = 0.0), revnixParseLength("0"))
        assertEquals(RevnixLength(fraction = 0.5), revnixParseLength("50%"))
        assertEquals(RevnixLength(fraction = -0.5), revnixParseLength("-50%"))
        assertEquals(RevnixLength(fraction = 1.0), revnixParseLength(" 100% "))
    }

    @Test
    fun `calc carries both halves`() {
        // The ticket-notch clips are authored as calc(100% - 16px); reading
        // only one half puts the notch at the wrong edge.
        assertEquals(RevnixLength(1.0, -16.0), revnixParseLength("calc(100% - 16px)"))
        assertEquals(RevnixLength(0.5, 4.0), revnixParseLength("calc(50% + 4px)"))
    }

    @Test
    fun `unreadable length is declined rather than guessed`() {
        assertNull(revnixParseLength("var(--x)"))
        assertNull(revnixParseLength("4rem"))
        assertNull(revnixParseLength(""))
        assertNull(revnixParseLength(null))
    }

    @Test
    fun `length resolves as multiply-add`() {
        val notch = assertNotNull(revnixParseLength("calc(100% - 16px)"))
        assertEquals(184.0, notch.resolvedAgainst(200.0))
        assertFalse(notch.isAbsolute)
        assertTrue(assertNotNull(revnixParseLength("12px")).isAbsolute)
    }

    // ——— translate ———

    @Test
    fun `translate reads the badge centring idiom`() {
        val t = assertNotNull(revnixParseTranslate("-50% 0"))
        assertEquals(RevnixLength(fraction = -0.5), t.x)
        assertEquals(RevnixLength(), t.y)
        assertFalse(t.isAbsolute)
        // The badge is pulled back by half its OWN width, not the parent's.
        assertEquals(-60.0, t.x.resolvedAgainst(120.0))
    }

    @Test
    fun `translate reads the px form and needs no measuring`() {
        val t = assertNotNull(revnixParseTranslate("0 -8px"))
        assertEquals(-8.0, t.y.px)
        assertTrue(t.isAbsolute)
    }

    @Test
    fun `single component translate leaves y at zero`() {
        val t = assertNotNull(revnixParseTranslate("12px"))
        assertEquals(12.0, t.x.px)
        assertEquals(RevnixLength(), t.y)
    }

    @Test
    fun `unreadable translate moves nothing`() {
        assertNull(revnixParseTranslate("nonsense"))
        assertNull(revnixParseTranslate(""))
        assertNull(revnixParseTranslate(null))
    }

    // ——— clip-path ———

    @Test
    fun `polygon reads a triangle`() {
        val points = assertNotNull(revnixParsePolygon("polygon(50% 0,100% 100%,0 100%)"))
        assertEquals(3, points.size)
        assertEquals(RevnixLength(fraction = 0.5), points[0].x)
        assertEquals(RevnixLength(fraction = 1.0), points[1].y)
    }

    @Test
    fun `polygon keeps calc points together`() {
        // The space inside calc() must NOT split the point in two — this is
        // the ticket notch, and splitting naively yields garbage vertices.
        val css = "polygon(0 0,100% 0,100% calc(100% - 16px),50% 100%,0 calc(100% - 16px))"
        val points = assertNotNull(revnixParsePolygon(css))
        assertEquals(5, points.size)
        assertEquals(RevnixLength(1.0, -16.0), points[2].y)
        assertEquals(RevnixLength(1.0, -16.0), points[4].y)
    }

    @Test
    fun `polygon reads the 32-point starburst`() {
        val points = assertNotNull(revnixParsePolygon(STARBURST))
        assertEquals(32, points.size)
    }

    @Test
    fun `leading fill rule is accepted and ignored`() {
        val points = assertNotNull(revnixParsePolygon("polygon(evenodd, 0 0, 100% 0, 50% 100%)"))
        assertEquals(3, points.size)
    }

    @Test
    fun `non-polygon clip is declined`() {
        // Declining leaves the block its full rectangle. Guessing can clip a
        // block away to nothing, which loses copy.
        assertNull(revnixParsePolygon("inset(10px)"))
        assertNull(revnixParsePolygon("circle(50%)"))
        assertNull(revnixParsePolygon(null))
    }

    @Test
    fun `degenerate polygon is declined`() {
        // Two points describe a line, which would erase the block.
        assertNull(revnixParsePolygon("polygon(0 0,100% 100%)"))
        assertNull(revnixParsePolygon("polygon(0 0,100% 0,50%)"))
    }

    // ——— convexity: what decides whether Android can clip at all ———

    @Test
    fun `the four shipped convex clips are classified convex`() {
        // Android's Outline refuses a concave path, so a wrong answer here is
        // the difference between a clip that draws and one that does nothing.
        val convex = listOf(
            "polygon(50% 0,100% 100%,0 100%)",
            "polygon(0 0,100% 0,100% calc(100% - 16px),50% 100%,0 calc(100% - 16px))",
            "polygon(0 0,86% 0,100% 50%,86% 100%,0 100%)",
            "polygon(0 0,88% 0,100% 50%,88% 100%,0 100%)",
        )
        for (css in convex) {
            assertTrue(assertNotNull(revnixParsePolygon(css)).revnixIsConvex(), css)
        }
    }

    @Test
    fun `the ticket notch stays convex at a real box size`() {
        // calc(100% - 16px) only makes sense against a box in px. A unit
        // box resolves it to -15 and turns the pentagon inside out.
        val notch = assertNotNull(
            revnixParsePolygon(
                "polygon(0 0,100% 0,100% calc(100% - 16px),50% 100%,0 calc(100% - 16px))",
            ),
        )
        assertTrue(notch.revnixIsConvex(200.0, 120.0))
        assertTrue(notch.revnixIsConvex())
    }

    @Test
    fun `the starburst is classified concave`() {
        assertFalse(assertNotNull(revnixParsePolygon(STARBURST)).revnixIsConvex())
    }

    @Test
    fun `collinear vertices do not make a shape concave`() {
        // A rectangle with a redundant mid-edge point is still convex; calling
        // it concave would lose a clip the platform can draw.
        val css = "polygon(0 0,50% 0,100% 0,100% 100%,0 100%)"
        assertTrue(assertNotNull(revnixParsePolygon(css)).revnixIsConvex())
    }

    // ——— fillSize ———

    @Test
    fun `fillSize reads the tile forms`() {
        assertEquals(
            RevnixFillSize.Tile(RevnixLength(px = 18.0), RevnixLength(px = 18.0)),
            revnixParseFillSize("18px 18px"),
        )
        assertEquals(
            RevnixFillSize.Tile(RevnixLength(px = 32.0), RevnixLength(px = 16.0)),
            revnixParseFillSize("32px 16px"),
        )
        // One length squares the tile, as CSS does for these washes.
        assertEquals(
            RevnixFillSize.Tile(RevnixLength(px = 6.0), RevnixLength(px = 6.0)),
            revnixParseFillSize("6px"),
        )
        assertEquals(RevnixFillSize.Cover, revnixParseFillSize("cover"))
        assertEquals(RevnixFillSize.Contain, revnixParseFillSize("contain"))
    }

    @Test
    fun `degenerate fillSize is declined`() {
        assertNull(revnixParseFillSize("0 0"))
        assertNull(revnixParseFillSize(""))
        assertNull(revnixParseFillSize(null))
    }

    // ——— the model carries all four ———

    @Test
    fun `all four fields survive decoding`() {
        val s = assertNotNull(
            style(
                """
                {"clipPath":"polygon(50% 0,100% 100%,0 100%)","translate":"-50% 0",
                 "fillSize":"18px 18px","textWrap":"pretty"}
                """,
            ),
        )
        assertEquals("polygon(50% 0,100% 100%,0 100%)", s.clipPath)
        assertEquals("-50% 0", s.translate)
        assertEquals("18px 18px", s.fillSize)
        assertEquals("pretty", s.textWrap)
    }

    @Test
    fun `all four fields merge like selectedStyle`() {
        // A plan card's selectedStyle merges over its base style. A field the
        // merge forgets is a field that cannot change on selection.
        val base = assertNotNull(
            style(
                """
                {"clipPath":"polygon(0 0,100% 0,50% 100%)","translate":"0 0",
                 "fillSize":"6px 6px","textWrap":"pretty"}
                """,
            ),
        )
        val selected = assertNotNull(
            style(
                """
                {"clipPath":"polygon(0 0,100% 0,100% 100%)","translate":"-50% 0",
                 "fillSize":"18px 18px","textWrap":"balance"}
                """,
            ),
        )
        val merged = base.merging(selected)
        assertEquals("polygon(0 0,100% 0,100% 100%)", merged.clipPath)
        assertEquals("-50% 0", merged.translate)
        assertEquals("18px 18px", merged.fillSize)
        assertEquals("balance", merged.textWrap)
    }

    @Test
    fun `merge leaves unset geometry alone`() {
        val base = assertNotNull(
            style(
                """
                {"clipPath":"polygon(0 0,100% 0,50% 100%)","translate":"-50% 0",
                 "fillSize":"6px 6px","textWrap":"pretty"}
                """,
            ),
        )
        val merged = base.merging(assertNotNull(style("""{"fill":"#ffffff"}""")))
        assertEquals("polygon(0 0,100% 0,50% 100%)", merged.clipPath)
        assertEquals("-50% 0", merged.translate)
        assertEquals("6px 6px", merged.fillSize)
        assertEquals("pretty", merged.textWrap)
    }

    @Test
    fun `an unreadable geometry value costs only itself`() {
        // Decoding stays total: a style whose translate is the wrong TYPE
        // keeps every other field rather than failing the block.
        val s = assertNotNull(
            style("""{"translate":42,"fill":"#101014","clipPath":"polygon(0 0,100% 0,50% 100%)"}"""),
        )
        assertNull(s.translate)
        assertEquals("#101014", s.fill)
        assertEquals("polygon(0 0,100% 0,50% 100%)", s.clipPath)
    }

    /** Decodes a style the way a published document reaches the renderer. */
    private fun style(json: String): BlockStyle? =
        BlockStyle.from(Json.parseToJsonElement(json.trimIndent()))

    private companion object {
        const val STARBURST =
            "polygon(50% 0%,57% 9%,68% 4%,72% 15%,84% 13%,84% 25%,96% 27%," +
                "92% 38%,100% 45%,93% 54%,98% 65%,88% 69%,89% 81%,77% 80%,73% 92%," +
                "62% 87%,54% 97%,46% 88%,35% 94%,30% 83%,18% 84%,20% 72%,8% 68%," +
                "14% 58%,5% 50%,13% 42%,7% 31%,18% 28%,17% 16%,29% 17%,32% 5%,43% 9%)"
    }
}
