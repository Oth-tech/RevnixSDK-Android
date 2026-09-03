package com.revnix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The selected-context half of the render contract (REV-262).
 *
 * `paywall-selection-wire.json` is a byte-identical copy of the fixture every
 * Revnix renderer walks in its own suite: a document with two pinned plan
 * cards whose descendants carry `selectedStyle` and `visibility`, a renewal
 * line outside any card, and four selection cases. Each case pins the
 * effective style, the visibility and the resolved copy of named blocks. If
 * this passes on all seven renderers they draw the same screen.
 */
class PaywallSelectionWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): JsonObject =
        json.parseToJsonElement(
            javaClass.classLoader!!.getResourceAsStream("paywall-selection-wire.json")!!
                .bufferedReader().readText(),
        ).jsonObject

    private fun packages(root: JsonObject): List<BlockPackage> =
        root["packages"]!!.jsonArray.map { it.jsonObject }.map { o ->
            BlockPackage(
                packageId = o["packageId"]!!.jsonPrimitive.content,
                title = o["title"]!!.jsonPrimitive.content,
                priceLabel = o["priceLabel"]!!.jsonPrimitive.content,
                period = o["period"]?.jsonPrimitive?.contentOrNull,
                amountMinor = o["amountMinor"]?.jsonPrimitive?.longOrNull,
                currency = o["currency"]?.jsonPrimitive?.contentOrNull,
            )
        }

    /** A case input that is JSON `null` means "the host passed nothing". */
    private fun optionalString(o: JsonObject, key: String): String? {
        val v = o[key] ?: return null
        if (v is JsonNull) return null
        return v.jsonPrimitive.contentOrNull
    }

    /** The style field the fixture names, read off the resolved style. */
    private fun field(style: BlockStyle?, key: String): Any? = when (key) {
        "fill" -> style?.fill
        "textColor" -> style?.textColor
        "borderColor" -> style?.borderColor
        "borderWidth" -> style?.borderWidth
        "radius" -> style?.radius
        "opacity" -> style?.opacity
        "fontSize" -> style?.fontSize
        "fontWeight" -> style?.fontWeight
        "padding" -> style?.padding
        "gap" -> style?.gap
        else -> fail("the fixture names a style field this test does not read: $key")
    }

    private fun List<ResolvedBlock>.byId(id: String): ResolvedBlock =
        firstOrNull { it.block.id == id } ?: fail("block $id was not resolved")

    // ——— the wire contract ———

    @Test
    fun `the fixture parses with selectedStyle and visibility on every level`() {
        val root = fixture()
        val doc = assertNotNull(PaywallBlockDoc.parse(root["doc"]))
        assertEquals("#00ff00", doc.accent)
        val plans = doc.blocks.first() as PaywallBlock.Card
        val c0 = plans.children[0] as PaywallBlock.Card
        assertEquals(0, c0.packageIndex)
        assertEquals("#222222", c0.selectedStyle?.fill)
        val ring = c0.children[0] as PaywallBlock.Card
        assertEquals("@accent", ring.selectedStyle?.borderColor)
        val dot = ring.children[0] as PaywallBlock.Card
        assertEquals(BlockVisibility.Selected, dot.visibility)
        val off = c0.children[3] as PaywallBlock.Text
        assertEquals(BlockVisibility.Unselected, off.visibility)
        val rootHidden = doc.blocks[2] as PaywallBlock.Text
        assertEquals(BlockVisibility.Selected, rootHidden.visibility)
    }

    @Test
    fun `every case resolves the styles, visibility and copy the contract pins`() {
        val root = fixture()
        val doc = assertNotNull(PaywallBlockDoc.parse(root["doc"]))
        val packages = packages(root)
        val cases = root["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 4, "the fixture carries at least four cases")

        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val selectedId = revnixSelectedPackageId(
                packages,
                optionalString(case, "selected"),
                null,
                optionalString(case, "highlight"),
            )
            val resolved = revnixResolveBlocks(doc, packages, selectedId)

            for ((id, expected) in case["styles"]?.jsonObject.orEmpty()) {
                val style = resolved.byId(id).style
                for ((key, value) in expected.jsonObject) {
                    val actual = field(style, key)
                    val primitive = value as JsonPrimitive
                    if (primitive.isString) {
                        assertEquals(primitive.content, actual, "$name: $id.$key")
                    } else {
                        assertEquals(primitive.doubleOrNull, actual, "$name: $id.$key")
                    }
                }
            }
            for (id in case["visible"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }) {
                assertTrue(resolved.byId(id).visible, "$name: $id should be visible")
            }
            for (id in case["hidden"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }) {
                assertFalse(resolved.byId(id).visible, "$name: $id should be hidden")
            }
            for ((id, expected) in case["texts"]?.jsonObject.orEmpty()) {
                assertEquals(expected.jsonPrimitive.content, resolved.byId(id).text, "$name: $id text")
            }
        }
    }

    // ——— the pure rules, one at a time ———

    private val monthly = BlockPackage("monthly", "Monthly", "$9.99", "monthly", 999, "USD")
    private val yearly = BlockPackage("yearly", "Yearly", "$59.99", "annual", 5999, "USD")
    private val offered = listOf(monthly, yearly)

    @Test
    fun `the selected package is the first offered candidate, then the first package`() {
        assertEquals("yearly", revnixSelectedPackageId(offered, "yearly", "monthly", null))
        assertEquals("monthly", revnixSelectedPackageId(offered, "lifetime", "monthly", "yearly"))
        assertEquals("yearly", revnixSelectedPackageId(offered, null, null, "yearly"))
        assertEquals("monthly", revnixSelectedPackageId(offered, null, null, null))
        assertNull(revnixSelectedPackageId(emptyList(), "monthly"))
    }

    @Test
    fun `selectedStyle applies to any block in selected context and nowhere else`() {
        val text = PaywallBlock.Text(
            "t", "x", style = BlockStyle(fontWeight = 400.0), selectedStyle = BlockStyle(fontWeight = 700.0),
        )
        assertEquals(700.0, revnixEffectiveStyle(text, revnixPackageContext(monthly, "monthly"))?.fontWeight)
        assertEquals(400.0, revnixEffectiveStyle(text, revnixPackageContext(monthly, "yearly"))?.fontWeight)
        assertEquals(400.0, revnixEffectiveStyle(text, BlockSelectionContext.NONE)?.fontWeight)
        // No base style: the selected one still applies, and null stays null.
        val bare = PaywallBlock.Line("l", selectedStyle = BlockStyle(fill = "@accent"))
        assertEquals("@accent", revnixEffectiveStyle(bare, revnixPackageContext(monthly, "monthly"))?.fill)
        assertNull(revnixEffectiveStyle(PaywallBlock.Line("l2"), revnixPackageContext(monthly, "monthly")))
    }

    @Test
    fun `visibility follows the context and never hides a root block`() {
        val onlySelected = PaywallBlock.Text("a", "x", visibility = BlockVisibility.Selected)
        val onlyUnselected = PaywallBlock.Text("b", "x", visibility = BlockVisibility.Unselected)
        val selected = revnixPackageContext(monthly, "monthly")
        val unselected = revnixPackageContext(monthly, "yearly")
        assertTrue(revnixIsBlockVisible(onlySelected, selected))
        assertFalse(revnixIsBlockVisible(onlySelected, unselected))
        assertFalse(revnixIsBlockVisible(onlyUnselected, selected))
        assertTrue(revnixIsBlockVisible(onlyUnselected, unselected))
        assertTrue(revnixIsBlockVisible(onlySelected, BlockSelectionContext.NONE))
        assertTrue(revnixIsBlockVisible(onlyUnselected, BlockSelectionContext.NONE))
    }

    @Test
    fun `a plain card inherits its parent's context and a pinned one starts its own`() {
        val parent = revnixPackageContext(yearly, "yearly")
        val plain = PaywallBlock.Card("p")
        assertEquals(parent, revnixCardContext(plain, parent, offered, "yearly"))
        val pinned = PaywallBlock.Card("q", packageIndex = 0)
        assertEquals(revnixPackageContext(monthly, "yearly"), revnixCardContext(pinned, parent, offered, "yearly"))
        // A card pinned past the offering is dropped.
        assertNull(revnixCardContext(PaywallBlock.Card("r", packageIndex = 5), parent, offered, "yearly"))
    }

    @Test
    fun `tags outside a package card resolve against the selected package`() {
        val doc = assertNotNull(
            PaywallBlockDoc.parse(
                json.parseToJsonElement(
                    """{"version":1,"blocks":[{"id":"f","type":"text","text":"then {price}/{period_short}"}]}""",
                ),
            ),
        )
        assertEquals("then $59.99/yr", revnixResolveBlocks(doc, offered, "yearly").byId("f").text)
        assertEquals("then $9.99/mo", revnixResolveBlocks(doc, offered, "monthly").byId("f").text)
        // With nothing selected (no packages) the tag stays visible, never wrong.
        assertEquals("then {price}/{period_short}", revnixResolveBlocks(doc, emptyList(), null).byId("f").text)
    }

    @Test
    fun `a repeat card yields one context per package`() {
        val doc = assertNotNull(
            PaywallBlockDoc.parse(
                json.parseToJsonElement(
                    """{"version":1,"blocks":[{"id":"rep","type":"card","repeat":"packages",
                       "children":[{"id":"on","type":"text","text":"{title}","visibility":"selected"}]}]}""",
                ),
            ),
        )
        val resolved = revnixResolveBlocks(doc, offered, "yearly")
        val captions = resolved.filter { it.block.id == "on" }
        assertEquals(listOf("Monthly", "Yearly"), captions.map { it.text })
        assertEquals(listOf(false, true), captions.map { it.visible })
    }

    @Test
    fun `a conditional close does not suppress the fallback chip`() {
        val conditional = listOf(
            PaywallBlock.Button("x", "Not now", action = BlockAction.Close, visibility = BlockVisibility.Selected),
        )
        assertFalse(revnixHasCloseAction(conditional))
        val certain = listOf(PaywallBlock.Button("x", "Not now", action = BlockAction.Close))
        assertTrue(revnixHasCloseAction(certain))
    }
}
