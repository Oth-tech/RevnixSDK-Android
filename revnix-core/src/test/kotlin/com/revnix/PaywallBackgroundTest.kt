package com.revnix

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screen background: the wire contract, the layer stack and the CSS
 * gradient parser.
 *
 * `paywall-background-wire.json` is a byte-identical copy of the fixture the
 * other five renderers decode in their own suites — a real document the
 * dashboard published from its background library. It is the closest thing
 * this SDK has to a cross-repo contract test, and it exists because the six
 * renderers previously disagreed about the ground's key with nothing to catch
 * it.
 */
class PaywallBackgroundTest {

    private fun golden(): String =
        javaClass.classLoader!!.getResourceAsStream("paywall-background-wire.json")!!
            .bufferedReader().readText()

    private val goldenGround =
        "radial-gradient(120% 85% at 50% 0%, #D6FF3F38 0%, #D6FF3F00 58%), " +
            "linear-gradient(180deg, #111820 0%, #07090C 100%)"

    // ——— the wire contract ———

    @Test
    fun `a real published document resolves to its gradient ground, not black`() {
        val doc = assertNotNull(PaywallBlockDoc.parse(Json.parseToJsonElement(golden())))
        assertEquals(goldenGround, doc.background)
    }

    @Test
    fun `the ground key is color`() {
        val bg = Json.parseToJsonElement("""{"color":"#0B0D10"}""")
        assertEquals("#0B0D10", revnixBackgroundGround(bg))
    }

    @Test
    fun `the legacy ground key is still accepted`() {
        val bg = Json.parseToJsonElement("""{"ground":"#0B0D10"}""")
        assertEquals("#0B0D10", revnixBackgroundGround(bg))
    }

    @Test
    fun `a plain string is the legacy ground`() {
        assertEquals("#0B0D10", revnixBackgroundGround(Json.parseToJsonElement(""""#0B0D10"""")))
    }

    @Test
    fun `an empty or absent ground is null rather than an empty paint`() {
        assertNull(revnixBackgroundGround(Json.parseToJsonElement("""{"color":""}""")))
        assertNull(revnixBackgroundGround(Json.parseToJsonElement("{}")))
        assertNull(revnixBackgroundGround(null))
    }

    // ——— the layer stack ———

    @Test
    fun `a legacy string resolves to exactly one ground layer`() {
        val layers = revnixBackgroundLayers(Json.parseToJsonElement(""""#0A0B0D""""))
        assertEquals("#0A0B0D", layers.ground)
        assertNull(layers.image)
        assertNull(layers.overlay)
        assertTrue(layers.isGroundOnly)
    }

    @Test
    fun `a photo carries its fit, focal point, opacity and blur`() {
        val layers = revnixBackgroundLayers(
            Json.parseToJsonElement(
                """{"color":"#000","image":{"url":"https://x/y.jpg","fit":"contain",
                   "focalX":20,"focalY":35,"opacity":70,"blur":8}}""",
            ),
        )
        val image = assertNotNull(layers.image)
        assertEquals("https://x/y.jpg", image.url)
        assertEquals(RevnixBackgroundFit.CONTAIN, image.fit)
        assertEquals(20.0, image.focalX)
        assertEquals(35.0, image.focalY)
        assertEquals(0.7, image.opacity)
        assertEquals(8.0, image.blur)
        assertTrue(!layers.isGroundOnly)
    }

    @Test
    fun `photo defaults are cover, centred, opaque and unblurred`() {
        val layers = revnixBackgroundLayers(
            Json.parseToJsonElement("""{"image":{"url":"https://x/y.jpg"}}"""),
        )
        val image = assertNotNull(layers.image)
        assertEquals(RevnixBackgroundFit.COVER, image.fit)
        assertEquals(50.0, image.focalX)
        assertEquals(50.0, image.focalY)
        assertEquals(1.0, image.opacity)
        assertNull(image.blur)
    }

    @Test
    fun `layers that would draw nothing are dropped rather than emitted`() {
        // A zero-opacity photo and a urlless one are both no-ops; emitting them
        // would cost a view that paints nothing.
        val noUrl = revnixBackgroundLayers(Json.parseToJsonElement("""{"image":{"fit":"cover"}}"""))
        assertNull(noUrl.image)
        val transparent = revnixBackgroundLayers(
            Json.parseToJsonElement("""{"image":{"url":"https://x/y.jpg","opacity":0}}"""),
        )
        assertNull(transparent.image)
        val noScrim = revnixBackgroundLayers(
            Json.parseToJsonElement("""{"overlay":{"fill":"#000","opacity":0}}"""),
        )
        assertNull(noScrim.overlay)
    }

    @Test
    fun `focal point and opacity are clamped to their ranges`() {
        val layers = revnixBackgroundLayers(
            Json.parseToJsonElement(
                """{"image":{"url":"https://x/y.jpg","focalX":-40,"focalY":900,"opacity":400}}""",
            ),
        )
        val image = assertNotNull(layers.image)
        assertEquals(0.0, image.focalX)
        assertEquals(100.0, image.focalY)
        assertEquals(1.0, image.opacity)
    }

    @Test
    fun `a scrim carries its fill and opacity`() {
        val layers = revnixBackgroundLayers(
            Json.parseToJsonElement("""{"overlay":{"fill":"#000000","opacity":40}}"""),
        )
        val overlay = assertNotNull(layers.overlay)
        assertEquals("#000000", overlay.fill)
        assertEquals(0.4, overlay.opacity)
    }

    // ——— @bg base colour ———

    @Test
    fun `a stacked gradient answers with the BOTTOM layer, not the glow on top`() {
        // In CSS the first-listed layer paints on top. The fixture stacks a
        // translucent lime glow over a near-black base; answering with the glow
        // would tint the whole screen lime.
        assertEquals("#111820", revnixBackgroundBaseColor(goldenGround))
    }

    @Test
    fun `a single gradient answers with its first stop`() {
        assertEquals("#111820", revnixBackgroundBaseColor("linear-gradient(180deg, #111820, #07090C)"))
        assertEquals(
            "rgba(0, 0, 0, 0.5)",
            revnixBackgroundBaseColor("linear-gradient(180deg, rgba(0, 0, 0, 0.5), rgba(0, 0, 0, 1))"),
        )
    }

    @Test
    fun `a fully transparent stop is skipped, since it says nothing about the ground`() {
        assertEquals(
            "#111820",
            revnixBackgroundBaseColor("linear-gradient(180deg, #D6FF3F00 0%, #111820 100%)"),
        )
    }

    @Test
    fun `a flat colour answers with itself, and nothing answers black`() {
        assertEquals("#0A0B0D", revnixBackgroundBaseColor("#0A0B0D"))
        assertEquals("#000000", revnixBackgroundBaseColor(null))
        assertEquals("#000000", revnixBackgroundBaseColor("   "))
    }

    // ——— the CSS gradient parser ———

    private fun parse(css: String) = revnixParseCssGradients(css) { parseColor(it) }

    @Test
    fun `a flat colour is not a gradient`() {
        assertTrue(parse("#0A0B0D").isEmpty())
    }

    @Test
    fun `180deg runs straight down, which is the library's most common recipe`() {
        val gradients = parse("linear-gradient(180deg, #111820 0%, #07090C 100%)")
        assertEquals(1, gradients.size)
        val linear = gradients[0] as RevnixGradient.Linear
        assertEquals(0.0, linear.dirX, 0.0001)
        assertEquals(1.0, linear.dirY, 0.0001)
        assertEquals(2, linear.stops.size)
        assertEquals(0.0, linear.stops[0].position)
        assertEquals(1.0, linear.stops[1].position)
    }

    @Test
    fun `135deg runs corner to corner`() {
        val linear = parse("linear-gradient(135deg, #000 0%, #fff 100%)")[0] as RevnixGradient.Linear
        assertEquals(1.0, linear.dirX, 0.0001)
        assertEquals(1.0, linear.dirY, 0.0001)
    }

    @Test
    fun `a radial gradient keeps its centre and extent`() {
        val radial = parse(
            "radial-gradient(120% 85% at 50% 0%, #D6FF3F38 0%, #D6FF3F00 58%)",
        )[0] as RevnixGradient.Radial
        assertEquals(0.5, radial.centerX)
        assertEquals(0.0, radial.centerY)
        // CSS gives an ellipse; a platform gradient API is circular, so the
        // larger extent is used deliberately.
        assertEquals(1.2, radial.radius, 0.0001)
        assertEquals(0.58, radial.stops[1].position, 0.0001)
    }

    @Test
    fun `a stacked gradient comes back bottom first, reversing CSS's own order`() {
        // In CSS the FIRST layer paints on top. The renderer draws in sequence,
        // so the list is reversed on the way out — getting this backwards would
        // bury the glow under its own base.
        val gradients = parse(goldenGround)
        assertEquals(2, gradients.size)
        assertTrue(gradients[0] is RevnixGradient.Linear)
        assertTrue(gradients[1] is RevnixGradient.Radial)
    }

    @Test
    fun `commas inside rgba do not tear a stop in half`() {
        val linear = parse(
            "linear-gradient(180deg, rgba(0, 0, 0, 0.5) 0%, rgba(255, 255, 255, 1) 100%)",
        )[0] as RevnixGradient.Linear
        assertEquals(2, linear.stops.size)
    }

    @Test
    fun `stops with no position are interpolated the way CSS spaces them`() {
        val linear = parse("linear-gradient(180deg, #000, #888, #fff)")[0] as RevnixGradient.Linear
        assertEquals(0.0, linear.stops[0].position, 0.0001)
        assertEquals(0.5, linear.stops[1].position, 0.0001)
        assertEquals(1.0, linear.stops[2].position, 0.0001)
    }

    @Test
    fun `a keyword direction is understood as well as an angle`() {
        val linear = parse("linear-gradient(to bottom, #000, #fff)")[0] as RevnixGradient.Linear
        assertEquals(1.0, linear.dirY, 0.0001)
    }

    @Test
    fun `a one-stop or unparseable gradient is dropped rather than half-drawn`() {
        assertTrue(parse("linear-gradient(180deg, #000)").isEmpty())
        assertTrue(parse("conic-gradient(#000, #fff)").isEmpty())
        assertTrue(parse("linear-gradient(180deg, notacolour, alsonot)").isEmpty())
    }
}
