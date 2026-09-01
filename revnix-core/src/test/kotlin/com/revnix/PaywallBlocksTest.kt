package com.revnix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Designed-paywall tests.
 *
 * The block document is authored by a dashboard that ships independently of
 * this SDK and reaches the app over the network, so the emphasis here is the
 * two guarantees an app cannot be patched into later: a document from a NEWER
 * dashboard still parses and still draws, and a tag with no data behind it
 * never resolves to a price the store will not charge.
 *
 * These cover the model and its parser, which is the whole of the portable
 * logic; the View renderer that consumes it lives in revnix-android and needs
 * an instrumented device, so it is exercised by hand rather than here.
 */
class PaywallBlocksTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun doc(body: String): PaywallBlockDoc? =
        PaywallBlockDoc.parse(json.parseToJsonElement(body))

    private val palette = """
        "version":1,"background":"#101014","textColor":"#F5F7FA",
        "accent":"#6478ff","accentInk":"#0B0D10"
    """.trimIndent()

    private val annual = BlockPackage("annual", "Annual", "$59.99", "annual", 5999, "USD")
    private val monthly = BlockPackage("monthly", "Monthly", "$9.99", "monthly", 999, "USD")
    private val packages = listOf(annual, monthly)

    // ——— the nine block types ———

    @Test
    fun `parses all nine block types`() {
        val parsed = doc(
            """
            {$palette,"blocks":[
              {"id":"t","type":"text","text":"Headline"},
              {"id":"i","type":"image","placeholder":"hero"},
              {"id":"l","type":"list","items":[{"title":"Offline downloads"}]},
              {"id":"p","type":"products"},
              {"id":"b","type":"button","label":"Continue"},
              {"id":"k","type":"links"},
              {"id":"n","type":"line"},
              {"id":"s","type":"spacer","flex":true},
              {"id":"c","type":"card","layout":"row","children":[{"id":"c1","type":"text","text":"in"}]}
            ]}
            """,
        )
        assertNotNull(parsed)
        assertEquals(9, parsed.blocks.size)
        assertEquals("Headline", assertIs<PaywallBlock.Text>(parsed.blocks[0]).text)
        assertIs<PaywallBlock.Image>(parsed.blocks[1])
        assertEquals("Offline downloads", assertIs<PaywallBlock.ListBlock>(parsed.blocks[2]).items[0].title)
        assertIs<PaywallBlock.Products>(parsed.blocks[3])
        assertEquals("Continue", assertIs<PaywallBlock.Button>(parsed.blocks[4]).label)
        assertIs<PaywallBlock.Links>(parsed.blocks[5])
        assertIs<PaywallBlock.Line>(parsed.blocks[6])
        assertEquals(true, assertIs<PaywallBlock.Spacer>(parsed.blocks[7]).flex)
        val card = assertIs<PaywallBlock.Card>(parsed.blocks[8])
        assertEquals("row", card.layout)
        assertEquals(1, card.children.size)
    }

    @Test
    fun `parses the four container layouts`() {
        for (layout in listOf("column", "row", "stack", "grid")) {
            val parsed = doc("""{$palette,"blocks":[{"id":"c","type":"card","layout":"$layout","children":[]}]}""")
            assertEquals(layout, assertIs<PaywallBlock.Card>(assertNotNull(parsed).blocks[0]).layout)
        }
    }

    @Test
    fun `parses a nested tree to full depth`() {
        val parsed = doc(
            """
            {$palette,"blocks":[{"id":"a","type":"card","children":[
              {"id":"b","type":"card","children":[
                {"id":"c","type":"card","children":[{"id":"d","type":"text","text":"deep"}]}
              ]}
            ]}]}
            """,
        )
        val a = assertIs<PaywallBlock.Card>(assertNotNull(parsed).blocks[0])
        val b = assertIs<PaywallBlock.Card>(a.children[0])
        val c = assertIs<PaywallBlock.Card>(b.children[0])
        assertEquals("deep", assertIs<PaywallBlock.Text>(c.children[0]).text)
    }

    // ——— a shipped app cannot be patched ———

    @Test
    fun `an unknown block type parses as Unknown and keeps its siblings`() {
        val parsed = doc(
            """
            {$palette,"blocks":[
              {"id":"a","type":"text","text":"before"},
              {"id":"x","type":"hologram","spin":true},
              {"id":"b","type":"text","text":"after"}
            ]}
            """,
        )
        assertNotNull(parsed)
        assertEquals(3, parsed.blocks.size)
        assertIs<PaywallBlock.Unknown>(parsed.blocks[1])
        assertEquals("before", assertIs<PaywallBlock.Text>(parsed.blocks[0]).text)
        assertEquals("after", assertIs<PaywallBlock.Text>(parsed.blocks[2]).text)
    }

    @Test
    fun `an unknown style field is ignored and the known ones survive`() {
        val parsed = doc(
            """
            {$palette,"blocks":[
              {"id":"t","type":"text","text":"x","style":{"fontSize":22,"teleport":"yes","radius":8}}
            ]}
            """,
        )
        val style = assertNotNull(assertIs<PaywallBlock.Text>(assertNotNull(parsed).blocks[0]).style)
        assertEquals(22.0, style.fontSize)
        assertEquals(8.0, style.radius)
    }

    @Test
    fun `a style field of an unexpected type costs only that field`() {
        // A future dashboard could widen a field's type. That must degrade to
        // "this SDK ignores it", not to a failed parse.
        val parsed = doc(
            """
            {$palette,"blocks":[
              {"id":"t","type":"text","text":"x","style":{"fontSize":"huge","radius":8}}
            ]}
            """,
        )
        val style = assertNotNull(assertIs<PaywallBlock.Text>(assertNotNull(parsed).blocks[0]).style)
        assertNull(style.fontSize)
        assertEquals(8.0, style.radius)
    }

    @Test
    fun `a malformed block parses as Unknown rather than throwing`() {
        val parsed = doc("""{$palette,"blocks":["not-a-block",{"id":"a","type":"text","text":"survivor"}]}""")
        assertNotNull(parsed)
        assertEquals(2, parsed.blocks.size)
        assertIs<PaywallBlock.Unknown>(parsed.blocks[0])
        assertEquals("survivor", assertIs<PaywallBlock.Text>(parsed.blocks[1]).text)
    }

    @Test
    fun `anything that is not a document returns null so the classic layout renders`() {
        assertNull(PaywallBlockDoc.parse(null))
        assertNull(doc("\"blocks\""))
        assertNull(doc("42"))
        assertNull(doc("[]"))
        assertNull(doc("{}"))
        assertNull(doc("""{$palette,"blocks":[]}"""), "a document with no blocks is not a design")
        assertNull(doc("""{$palette,"blocks":"nope"}"""))
    }

    @Test
    fun `a document missing its palette still parses on defaults`() {
        // Losing one colour must not lose the design; the screen falls back to
        // a readable palette rather than refusing to draw.
        val parsed = doc("""{"version":1,"blocks":[{"id":"t","type":"text","text":"x"}]}""")
        assertNotNull(parsed)
        assertEquals("#000000", parsed.background)
        assertEquals("#FFFFFF", parsed.textColor)
        assertEquals("#6478ff", parsed.accent)
    }

    @Test
    fun `a malformed tree never costs the whole config`() {
        // The critical degradation: if `blocks` cannot be read, the app must
        // still get a classic paywall it can sell from — never nothing.
        val config = json.decodeFromString<PaywallConfig>(
            """
            {"template":"focus","headline":"Go Pro","ctaLabel":"Continue",
             "features":[],"blocks":"this is not a document"}
            """,
        )
        assertEquals("Go Pro", config.headline)
        assertNull(PaywallBlockDoc.parse(config.blocks))
    }

    @Test
    fun `a config without blocks decodes unchanged`() {
        val config = json.decodeFromString<PaywallConfig>(
            """{"template":"minimal","headline":"Go Pro","ctaLabel":"Start","features":[]}""",
        )
        assertNull(config.blocks)
        assertEquals("minimal", config.template)
    }

    @Test
    fun `a config with blocks exposes the tree`() {
        val config = json.decodeFromString<PaywallConfig>(
            """
            {"template":"focus","headline":"Go Pro","ctaLabel":"Continue","features":[],
             "blocks":{$palette,"blocks":[{"id":"t","type":"text","text":"Designed"}]}}
            """,
        )
        val parsed = assertNotNull(PaywallBlockDoc.parse(config.blocks))
        assertEquals(1, parsed.blocks.size)
        assertEquals("#6478ff", parsed.accent)
    }

    // ——— tag variables ———

    @Test
    fun `price comes from the store not the design`() {
        assertEquals("$59.99", revnixResolveTags("{price}", annual, packages))
        assertEquals("Annual — $59.99", revnixResolveTags("{title} — {price}", annual, packages))
        assertEquals("every month", revnixResolveTags("every {period}", monthly, packages))
        assertEquals("$59.99/yr", revnixResolveTags("{price}/{period_short}", annual, packages))
    }

    @Test
    fun `a tag with no data behind it stays visible`() {
        // No period and no money: a guess here would be a price the store will
        // not charge, so the tag must remain on screen instead.
        val bare = BlockPackage("x", "Pro", "$1")
        assertEquals("{price_per_month}", revnixResolveTags("{price_per_month}", bare, listOf(bare)))
        assertEquals("{save_percent}", revnixResolveTags("{save_percent}", bare, listOf(bare)))
        assertEquals("{period}", revnixResolveTags("{period}", bare, listOf(bare)))
    }

    @Test
    fun `an unknown tag is left in place`() {
        assertEquals("{quantum_discount}", revnixResolveTags("{quantum_discount}", annual, packages))
    }

    @Test
    fun `saving is computed against the dearest plan`() {
        assertEquals("50%", revnixResolveTags("{save_percent}", annual, packages))
        // The dearest plan has nothing to beat, so its saving stays unresolved.
        assertEquals("{save_percent}", revnixResolveTags("{save_percent}", monthly, packages))
    }

    @Test
    fun `currencies without a minor unit are not divided by a hundred`() {
        assertEquals(1.0, revnixMinorUnits("JPY"))
        assertEquals(100.0, revnixMinorUnits("USD"))
        val yen = BlockPackage("y", "Year", "¥12,000", "annual", 12000, "JPY")
        // 12,000 yen a year is 1,000 a month — not 10.
        assertTrue(
            revnixResolveTags("{price_per_month}", yen, listOf(yen)).contains("1,000"),
            "a currency with no minor unit must not be divided by 100",
        )
    }

    @Test
    fun `an unclosed brace is left alone rather than eating the rest of the copy`() {
        assertEquals("Save {price on this", revnixResolveTags("Save {price on this", annual, packages))
    }

    @Test
    fun `text with no tags and a null package are untouched`() {
        assertEquals("Train smarter", revnixResolveTags("Train smarter", annual, packages))
        assertEquals("{price}", revnixResolveTags("{price}", null, packages))
    }

    // ——— palette tokens ———

    @Test
    fun `palette tokens resolve against the screen palette`() {
        val parsed = assertNotNull(doc("""{$palette,"blocks":[{"id":"t","type":"text","text":"x"}]}"""))
        assertEquals(0xFF6478FF.toInt(), revnixBlockColor("@accent", parsed))
        assertEquals(0xFFF5F7FA.toInt(), revnixBlockColor("@text", parsed))
        assertEquals(0xFF101014.toInt(), revnixBlockColor("@bg", parsed))
        assertEquals(0xFFFF0000.toInt(), revnixBlockColor("#ff0000", parsed))
        // A gradient has no single colour; the caller keeps its own default.
        assertNull(revnixBlockColor("linear-gradient(180deg,#000,#fff)", parsed))
        assertNull(revnixBlockColor("@nonsense", parsed))
        assertNull(revnixBlockColor(null, parsed))
    }

    @Test
    fun `a token alpha multiplies the colour's own`() {
        val parsed = assertNotNull(doc("""{$palette,"blocks":[{"id":"t","type":"text","text":"x"}]}"""))
        val faded = assertNotNull(revnixBlockColor("@text/12", parsed))
        assertEquals(0x1F, (faded ushr 24) and 0xFF, "12% of full opacity")
        assertEquals(0xF5F7FA, faded and 0xFFFFFF, "the colour itself is unchanged")
    }

    @Test
    fun `css rgba hex is reordered to android argb`() {
        val parsed = assertNotNull(doc("""{$palette,"blocks":[{"id":"t","type":"text","text":"x"}]}"""))
        // #RRGGBBAA in CSS, 0xAARRGGBB on Android — getting this backwards
        // paints the alpha as red.
        assertEquals(0x80FF0000.toInt(), revnixBlockColor("#ff000080", parsed))
        assertEquals(0x80FF0000.toInt(), revnixBlockColor("rgba(255, 0, 0, 0.5)", parsed))
    }

    @Test
    fun `a layered background reduces to its ground colour`() {
        // The dashboard's ground field is `color`. This test used to assert
        // `ground` — the name of the RESOLVED layer, which no writer has ever
        // emitted — and that is precisely why the black-screen bug shipped:
        // the wrong contract was green.
        val parsed = doc(
            """
            {"version":1,"background":{"color":"#0B0D10","image":{"url":"https://x/y.jpg"}},
             "textColor":"#fff","accent":"#6478ff","accentInk":"#000",
             "blocks":[{"id":"t","type":"text","text":"x"}]}
            """,
        )
        assertEquals("#0B0D10", assertNotNull(parsed).background)
    }

    @Test
    fun `a legacy ground key is still honoured so no published document breaks`() {
        val parsed = doc(
            """
            {"version":1,"background":{"ground":"#0B0D10"},
             "textColor":"#fff","accent":"#6478ff","accentInk":"#000",
             "blocks":[{"id":"t","type":"text","text":"x"}]}
            """,
        )
        assertEquals("#0B0D10", assertNotNull(parsed).background)
    }

    @Test
    fun `color wins over ground when a document somehow carries both`() {
        val parsed = doc(
            """
            {"version":1,"background":{"color":"#111820","ground":"#FF0000"},
             "textColor":"#fff","accent":"#6478ff","accentInk":"#000",
             "blocks":[{"id":"t","type":"text","text":"x"}]}
            """,
        )
        assertEquals("#111820", assertNotNull(parsed).background)
    }

    // ——— style values ———

    @Test
    fun `proportional and auto values survive parsing`() {
        val parsed = doc(
            """
            {$palette,"blocks":[{"id":"t","type":"text","text":"x","style":{
              "height":"78%","top":"50%","left":24,"marginTop":"auto","aspectRatio":"16/9","basis":250
            }}]}
            """,
        )
        val style = assertNotNull(assertIs<PaywallBlock.Text>(assertNotNull(parsed).blocks[0]).style)
        assertEquals(0.78, style.height?.fraction)
        assertNull(style.height?.px, "a percentage has no fixed pixel value")
        assertEquals(0.5, style.top?.fraction)
        assertEquals(24.0, style.left?.px)
        assertEquals(true, style.marginTop?.isAuto)
        assertEquals(16.0 / 9.0, style.aspectRatio?.ratio)
        assertEquals(250.0, style.basis)
    }

    @Test
    fun `per-side borders parse`() {
        val parsed = doc(
            """
            {$palette,"blocks":[{"id":"t","type":"text","text":"x","style":{
              "borderTop":"2px solid @accent","borderBottom":"1px solid @text/12"
            }}]}
            """,
        )
        val style = assertNotNull(assertIs<PaywallBlock.Text>(assertNotNull(parsed).blocks[0]).style)
        assertEquals("2px solid @accent", style.borderTop)
        assertEquals("1px solid @text/12", style.borderBottom)
    }

    @Test
    fun `flex shrink and wrap parse`() {
        val parsed = doc(
            """
            {$palette,"blocks":[{"id":"t","type":"text","text":"x","style":{
              "flex":1,"shrink":0,"wrap":true,"selfAlign":"start","items":"center","justify":"between"
            }}]}
            """,
        )
        val style = assertNotNull(assertIs<PaywallBlock.Text>(assertNotNull(parsed).blocks[0]).style)
        assertEquals(1.0, style.flex)
        assertEquals(0.0, style.shrink)
        assertEquals(true, style.wrap)
        assertEquals("start", style.selfAlign)
        assertEquals("center", style.items)
        assertEquals("between", style.justify)
    }

    @Test
    fun `selected style merges over the base style`() {
        val parsed = doc(
            """
            {$palette,"blocks":[{"id":"c","type":"card","repeat":"packages",
              "style":{"radius":16,"fill":"#111"},
              "selectedStyle":{"fill":"@accent/12"},
              "children":[]}]}
            """,
        )
        val card = assertIs<PaywallBlock.Card>(assertNotNull(parsed).blocks[0])
        val merged = (card.style ?: BlockStyle()).merging(card.selectedStyle)
        assertEquals("@accent/12", merged.fill, "the selected style wins where it sets a field")
        assertEquals(16.0, merged.radius, "and the base style survives where it does not")
    }

    @Test
    fun `grid track list and column count both parse`() {
        val parsed = doc(
            """
            {$palette,"blocks":[
              {"id":"g1","type":"card","layout":"grid","columns":3,"children":[]},
              {"id":"g2","type":"card","layout":"grid","gridColumns":"1fr 60px","children":[]}
            ]}
            """,
        )
        assertNotNull(parsed)
        assertEquals(3, assertIs<PaywallBlock.Card>(parsed.blocks[0]).columns)
        assertEquals("1fr 60px", assertIs<PaywallBlock.Card>(parsed.blocks[1]).gridColumns)
    }

    @Test
    fun `packageIndex and repeat parse`() {
        val parsed = doc(
            """
            {$palette,"blocks":[
              {"id":"a","type":"card","packageIndex":1,"children":[]},
              {"id":"b","type":"card","repeat":"packages","children":[]}
            ]}
            """,
        )
        assertNotNull(parsed)
        assertEquals(1, assertIs<PaywallBlock.Card>(parsed.blocks[0]).packageIndex)
        assertEquals("packages", assertIs<PaywallBlock.Card>(parsed.blocks[1]).repeat)
    }
}
