package com.revnix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * REV-271 designed-paywall localization.
 *
 * The dashboard writes the table; this SDK reads it. These are the parity
 * contract — the same cases run in revnix-app and in every other Revnix SDK,
 * so a paywall translated in the builder resolves identically everywhere.
 *
 * The guarantees that matter are the ones a shipped app cannot be patched out
 * of: an untranslated string must never render blank, a regional locale must
 * reach its language, and the overlay must disturb nothing but words.
 */
class PaywallLocalizationTest {

    private val json = """
        {
          "version": 1, "background": "#101014", "textColor": "#F5F7FA",
          "accent": "#6478ff", "accentInk": "#0B0D10",
          "defaultLocale": "en",
          "locales": {
            "es": {
              "hed.text": "Desbloquea Pro",
              "feats.items.0.title": "Modo sin conexión",
              "plans.priceTpl": "{price}/mes",
              "cta.label": "Continuar"
            },
            "pt_br": { "hed.text": "Desbloqueie o Pro" }
          },
          "blocks": [
            { "id": "hed", "type": "text", "text": "Unlock Pro", "style": { "fontSize": 28 } },
            { "id": "feats", "type": "list", "items": [
                { "title": "Offline mode", "description": "Take it anywhere" },
                { "title": "No ads" } ] },
            { "id": "wrap", "type": "card", "layout": "column", "children": [
                { "id": "plans", "type": "products", "titleTpl": "{title}", "priceTpl": "{price}/mo" },
                { "id": "cta", "type": "button", "label": "Continue" } ] }
          ]
        }
    """

    private fun doc(source: String = json): PaywallBlockDoc =
        assertNotNull(PaywallBlockDoc.parse(Json.parseToJsonElement(source)))

    @Test
    fun `normalizes tags to one canonical form`() {
        assertEquals("es-MX", revnixNormalizeLocale("es_mx"))
        assertEquals("pt-BR", revnixNormalizeLocale(" PT-br "))
        assertEquals("zh-Hans-CN", revnixNormalizeLocale("zh-hans-cn"))
        assertEquals("es-419", revnixNormalizeLocale("es-419"))
        // Junk must not become a language nobody can select.
        assertNull(revnixNormalizeLocale("english"))
        assertNull(revnixNormalizeLocale(""))
    }

    @Test
    fun `authored tags are normalized on parse`() {
        // "pt_br" was hand-written in the catalog; a device reporting "pt-BR"
        // must still find it.
        val localized = doc().localized("pt-BR")
        assertEquals("Desbloqueie o Pro", assertIs<PaywallBlock.Text>(localized.blocks[0]).text)
    }

    @Test
    fun `a regional locale falls back to its language, not to English`() {
        assertEquals(listOf("es"), revnixLocaleChain(listOf("es", "fr"), "es-MX", "en"))
        // Deterministic across devices: map order must not decide what a
        // customer reads.
        assertEquals(listOf("es-AR"), revnixLocaleChain(listOf("es-MX", "es-AR"), "es", null))
        assertEquals(emptyList(), revnixLocaleChain(listOf("es"), "ja", null))
    }

    @Test
    fun `swaps strings at every depth and keeps copy tags`() {
        val out = doc().localized("es-MX")
        assertEquals("Desbloquea Pro", assertIs<PaywallBlock.Text>(out.blocks[0]).text)
        val card = assertIs<PaywallBlock.Card>(out.blocks[2])
        assertEquals("Continuar", assertIs<PaywallBlock.Button>(card.children[1]).label)
        val products = assertIs<PaywallBlock.Products>(card.children[0])
        // The tag survives translation, so {price} still resolves after it.
        assertEquals("{price}/mes", products.priceTpl)
        assertEquals("{title}", products.titleTpl)
    }

    @Test
    fun `untranslated strings keep the authored copy`() {
        val list = assertIs<PaywallBlock.ListBlock>(doc().localized("es").blocks[1])
        assertEquals("Modo sin conexión", list.items[0].title)
        // Same item, untranslated description — and a wholly untranslated row.
        assertEquals("Take it anywhere", list.items[0].description)
        assertEquals("No ads", list.items[1].title)
    }

    @Test
    fun `an empty translation means untranslated, not blank`() {
        // An export/import round-trip leaves empty cells everywhere; honouring
        // them would ship a paywall with no CTA label.
        val blanked = json.replace("\"cta.label\": \"Continuar\"", "\"cta.label\": \"\"")
        val card = assertIs<PaywallBlock.Card>(doc(blanked).localized("es").blocks[2])
        assertEquals("Continue", assertIs<PaywallBlock.Button>(card.children[1]).label)
    }

    @Test
    fun `only words change`() {
        val original = doc()
        val out = original.localized("es")
        assertEquals(original.accent, out.accent)
        assertEquals(original.background, out.background)
        assertEquals("hed", out.blocks[0].id)
        assertEquals(28.0, assertIs<PaywallBlock.Text>(out.blocks[0]).style?.fontSize)
    }

    @Test
    fun `an unmatched locale renders the authored document`() {
        assertEquals("Unlock Pro", assertIs<PaywallBlock.Text>(doc().localized("ja").blocks[0]).text)
    }

    @Test
    fun `a malformed table costs the translations, never the paywall`() {
        val broken = """
            {"version":1,"background":"#000","textColor":"#fff","accent":"#6478ff",
             "accentInk":"#fff","locales":"nonsense",
             "blocks":[{"id":"hed","type":"text","text":"Unlock Pro"}]}
        """
        val parsed = doc(broken)
        assertTrue(parsed.localization.isEmpty)
        assertEquals("Unlock Pro", assertIs<PaywallBlock.Text>(parsed.localized("es").blocks[0]).text)
    }
}
