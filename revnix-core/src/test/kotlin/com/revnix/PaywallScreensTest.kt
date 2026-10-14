package com.revnix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class PaywallScreensTest {

    private val example = """
        {"version":1,"background":"#0B0B12","textColor":"#FFFFFF","accent":"#7C5CFF","accentInk":"#FFFFFF",
         "screens":[
           [{"id":"s1_t","type":"text","text":"Welcome"},
            {"id":"s1_n","type":"button","label":"Continue","action":"next"}],
           [{"id":"s2_t","type":"text","text":"Track every workout"},
            {"id":"s2_b","type":"text","text":"Back","action":"back"},
            {"id":"s2_x","type":"image","action":"close"},
            {"id":"s2_n","type":"button","label":"Continue","action":"next"}],
           "not a screen", [], [{"type":"sparkle"}]
         ],
         "blocks":[{"id":"p_t","type":"text","text":"Go Pro"},{"id":"p_p","type":"products"},
                   {"id":"p_b","type":"button","label":"Start free trial","action":"skip"}],
         "locales":{"es":{"s1_t.text":"Bienvenido","s1_n.label":"Continuar"}}}
    """.trimIndent()

    private fun parse(body: String) = assertNotNull(PaywallBlockDoc.parse(Json.parseToJsonElement(body)))

    @Test
    fun `screens parse in order with next and back, dropping malformed entries`() {
        val screens = parse(example).screens
        assertEquals(3, screens.size)
        assertEquals(BlockAction.Next, assertIs<PaywallBlock.Button>(screens[0][1]).action)
        assertEquals(BlockAction.Back, assertIs<PaywallBlock.Text>(screens[1][1]).action)
        assertIs<PaywallBlock.Unknown>(screens[2][0])
    }

    @Test
    fun `an unknown action is inert and a doc without screens has none`() {
        val doc = parse(example)
        assertNull(assertIs<PaywallBlock.Button>(doc.blocks[2]).action)
        assertTrue(parse("""{"blocks":[{"id":"a","type":"text","text":"x"}]}""").screens.isEmpty())
    }

    @Test
    fun `the locale overlay reaches screen blocks`() {
        val es = parse(example).localized("es")
        assertEquals("Bienvenido", assertIs<PaywallBlock.Text>(es.screens[0][0]).text)
        assertEquals("Continuar", assertIs<PaywallBlock.Button>(es.screens[0][1]).label)
    }

    @Test
    fun `close is detected per screen`() {
        val screens = parse(example).screens
        assertFalse(revnixHasCloseAction(screens[0]))
        assertTrue(revnixHasCloseAction(screens[1]))
    }
}
