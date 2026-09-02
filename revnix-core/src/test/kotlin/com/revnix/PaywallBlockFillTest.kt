package com.revnix

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Block fills: the half of a paint string that is NOT a plain colour.
 *
 * A `fill` is handed straight to CSS `background` by the dashboard, so it may
 * be a colour, a gradient, or a stack of them. This SDK parsed only the first
 * form and painted nothing for the others — 113 of the 250 shipped gallery
 * presets use a gradient somewhere, so "nothing" was the common case.
 *
 * Three separate defects are pinned here, because each of them alone was enough
 * to lose a gradient:
 *
 *  1. the fill was never routed through the gradient parser at all;
 *  2. `@bg` resolved to the RAW ground, so a `@bg` stop inside a gradient failed
 *     to parse and was dropped — and a gradient left with one stop does not
 *     parse either, taking the whole fill with it;
 *  3. a stop may carry TWO positions (`@accent 0 22%`), and reading only the
 *     last one turned every hard edge in the library into a smooth fade.
 */
class PaywallBlockFillTest {

    private fun doc(background: String = "#101014"): PaywallBlockDoc = assertNotNull(
        PaywallBlockDoc.parse(
            Json.parseToJsonElement(
                """
                {"version":1,"background":"$background","textColor":"#F5F7FA",
                 "accent":"#6478ff","accentInk":"#0B0D10",
                 "blocks":[{"id":"t","type":"text","text":"Hello"}]}
                """.trimIndent(),
            ),
        ),
    )

    private fun parse(css: String, doc: PaywallBlockDoc): List<RevnixGradient> =
        revnixParseCssGradients(css) { revnixBlockColor(it, doc) }

    // ——— resolving a fill ———

    @Test
    fun `a plain colour fill stays a plain colour`() {
        val fill = revnixBlockFill("#FF0000", doc())
        assertEquals(0xFFFF0000.toInt(), fill.color)
        assertTrue(fill.gradients.isEmpty())
    }

    @Test
    fun `an absent fill paints nothing`() {
        assertTrue(revnixBlockFill(null, doc()).isNone)
        assertTrue(revnixBlockFill("   ", doc()).isNone)
    }

    @Test
    fun `a gradient fill resolves to its layers and nothing under them`() {
        val fill = revnixBlockFill("linear-gradient(135deg, #112233 0%, #445566 100%)", doc())
        assertEquals(1, fill.gradients.size)
        assertNull(fill.color)
    }

    @Test
    fun `a translucent scrim is not backed by an opaque box`() {
        // 83 of the library's 139 gradient fills fade through a translucent
        // stop: they are drawn OVER the screen's photo so it shows through. A
        // flat base under them would make every one of those a solid block.
        val fill = revnixBlockFill(
            "linear-gradient(180deg, rgba(15,16,19,0.72) 0%, rgba(15,16,19,0.1) 32%, #0F1013 100%)",
            doc(),
        )
        assertEquals(1, fill.gradients.size)
        assertNull(fill.color)
    }

    @Test
    fun `a malformed position costs the position, not the stop`() {
        // The token comes off the colour either way. Leaving it attached made
        // the colour unparseable, dropping the stop — and a gradient left with
        // one stop does not parse at all, so one typo lost the whole fill.
        val layers = parse("linear-gradient(180deg, #112233 1.2.3%, #445566 100%)", doc())
        assertEquals(1, layers.size)
        assertEquals(2, layers[0].stops.size)
    }

    @Test
    fun `a stacked gradient keeps every layer, bottom first`() {
        val css =
            "radial-gradient(120% 90% at 86% 4%, #FF3D7F 0%, rgba(255,61,127,0) 48%)," +
                "linear-gradient(180deg, #1C1046 0%, #0E0722 100%)"
        val fill = revnixBlockFill(css, doc())
        assertEquals(2, fill.gradients.size)
        // CSS paints the FIRST-listed layer on top, so the list is reversed:
        // the linear base comes first, ready to be drawn under the glow.
        assertTrue(fill.gradients[0] is RevnixGradient.Linear)
        assertTrue(fill.gradients[1] is RevnixGradient.Radial)
    }

    @Test
    fun `the flat base is still what a colour-only field collapses to`() {
        // The base colour did not go away — it moved to the only places it is
        // correct: a field that can hold one colour, and the parse-failure
        // fallback. Both read the BOTTOM layer's first opaque stop.
        val css =
            "radial-gradient(120% 90% at 86% 4%, #FF3D7F 0%, rgba(255,61,127,0) 48%)," +
                "linear-gradient(180deg, #1C1046 0%, #0E0722 100%)"
        assertEquals(0xFF1C1046.toInt(), revnixBlockStrokeColor(css, doc()))
    }

    @Test
    fun `an unreadable fill falls back to a design colour and reports`() {
        val reported = mutableListOf<String>()
        val fill = revnixBlockFill(
            "repeating-linear-gradient(180deg, transparent 0 33px, #E2D2B6 33px 34px), #FBF3E4",
            doc(),
        ) { reported.add(it) }
        assertEquals(0xFFFBF3E4.toInt(), fill.color)
        assertTrue(fill.gradients.isEmpty())
        assertEquals(1, reported.size)
        assertTrue(reported[0].contains("unreadable fill"))
    }

    @Test
    fun `a black screen is never the fallback`() {
        // The regression this whole ticket exists for: an unreadable fill used
        // to leave the box unpainted over a #000000 screen.
        val fill = revnixBlockFill("conic-gradient(#123456, #654321)", doc())
        assertEquals(0xFF123456.toInt(), fill.color)
    }

    // ——— colour-only fields ———

    @Test
    fun `a gradient in a colour-only field collapses rather than disappearing`() {
        val reported = mutableListOf<String>()
        val colour = revnixBlockStrokeColor(
            "linear-gradient(90deg, #00FF00 0%, #0000FF 100%)",
            doc(),
        ) { reported.add(it) }
        assertEquals(0xFF00FF00.toInt(), colour)
        assertEquals(1, reported.size)
        assertTrue(reported[0].contains("flattened"))
    }

    @Test
    fun `a plain colour in a colour-only field reports nothing`() {
        val reported = mutableListOf<String>()
        assertEquals(
            0xFF6478FF.toInt(),
            revnixBlockStrokeColor("@accent", doc()) { reported.add(it) },
        )
        assertTrue(reported.isEmpty())
    }

    // ——— @bg over a gradient ground ———

    @Test
    fun `bg resolves to the ground's flat base, not the raw gradient`() {
        // The dashboard answers `@bg` with `backgroundBaseColor(...)` because it
        // feeds the token into color-mix(), which cannot take a gradient.
        val d = doc("linear-gradient(180deg, #231646 0%, #0C0C13 100%)")
        assertEquals(0xFF231646.toInt(), revnixBlockColor("@bg", d))
        assertNotNull(revnixBlockColor("@bg/50", d))
    }

    @Test
    fun `a bg stop inside a gradient no longer takes the whole fill with it`() {
        // Before the fix `@bg` returned null, the stop was dropped, and a
        // gradient left under two stops does not parse — so a fill the design
        // wrote as three stops painted nothing at all.
        val d = doc("linear-gradient(180deg, #231646 0%, #0C0C13 100%)")
        val layers = parse("linear-gradient(180deg, rgba(12,16,19,0.5) 0%, @bg 100%)", d)
        assertEquals(1, layers.size)
        assertEquals(2, layers[0].stops.size)
    }

    // ——— stop syntax the library actually ships ———

    @Test
    fun `a stop may carry two positions, which is a hard edge`() {
        // "@accent 0 22%" is the accent at BOTH 0 and 22%, then the next colour
        // starts at 22% — the progress-bar idiom, and a hard edge rather than
        // the smooth fade that reading one position produced.
        val layers = parse("linear-gradient(90deg, @accent 0 22%, #16203C 22%)", doc())
        assertEquals(1, layers.size)
        val stops = layers[0].stops
        assertEquals(3, stops.size)
        assertEquals(0.0, stops[0].position, 0.0001)
        assertEquals(0.22, stops[1].position, 0.0001)
        assertEquals(0.22, stops[2].position, 0.0001)
        assertEquals(0xFF6478FF.toInt(), stops[0].color)
        assertEquals(0xFF6478FF.toInt(), stops[1].color)
        assertEquals(0xFF16203C.toInt(), stops[2].color)
    }

    @Test
    fun `transparent is a colour the designs use`() {
        assertEquals(0, revnixBlockColor("transparent", doc()))
        val layers = parse("linear-gradient(90deg, @accent 0 60%, transparent 60%)", doc())
        assertEquals(1, layers.size)
        assertEquals(3, layers[0].stops.size)
    }

    @Test
    fun `a colour with spaces inside it is not torn apart by the stop parser`() {
        val layers = parse("linear-gradient(180deg, rgba(0, 0, 0, 0.5) 0%, #FFFFFF 100%)", doc())
        assertEquals(1, layers.size)
        assertEquals(128, (layers[0].stops[0].color ushr 24) and 0xFF)
    }

    @Test
    fun `a repeating pattern paints nothing rather than a stripe colour`() {
        // The colours inside a pattern are STRIPE colours. The library's
        // hairline grid is `#0E1B21` once every 26px; as a solid fill it is a
        // slab, which is a wrong answer rather than a degraded one.
        val reported = mutableListOf<String>()
        val fill = revnixBlockFill(
            "repeating-linear-gradient(180deg, #0E1B21 0 1px, @bg 1px 26px)",
            doc(),
        ) { reported.add(it) }
        assertTrue(fill.isNone)
        assertEquals(1, reported.size)
    }

    @Test
    fun `a pattern stacked over a ground still falls back to that ground`() {
        // The BOTTOM layer decides: here it is a plain colour, and a plain
        // colour is exactly the surface colour the box should take.
        val fill = revnixBlockFill(
            "repeating-linear-gradient(180deg, transparent 0 33px, #E2D2B6 33px 34px), #FBF3E4",
            doc(),
        )
        assertEquals(0xFFFBF3E4.toInt(), fill.color)
    }

    @Test
    fun `a unitless position is read rather than swallowing the stop`() {
        // CSS allows a unitless zero. Requiring a `%` made the whole
        // "#112233 0" argument the COLOUR, which failed to parse and dropped
        // the stop — and a gradient left with one stop does not parse at all.
        val layers = parse("linear-gradient(180deg, #112233 0, #445566 100%)", doc())
        assertEquals(1, layers.size)
        assertEquals(2, layers[0].stops.size)
        assertEquals(0.0, layers[0].stops[0].position, 0.0001)
    }

    @Test
    fun `a token stop with zero alpha is not chosen as the base`() {
        // `@accent/0` is transparent, but only once resolved — judging the
        // source text alone called it opaque and answered with an invisible
        // colour.
        val doc = doc()
        val colour = revnixBlockStrokeColor(
            "linear-gradient(180deg, @accent/0 0%, @accent/22 50%, @accent/0 100%)",
            doc,
        )
        assertEquals(revnixBlockColor("@accent/22", doc), colour)
    }

    @Test
    fun `an empty gradient list has no base colour`() {
        assertNull(revnixGradientBaseColor(emptyList()))
    }
}
