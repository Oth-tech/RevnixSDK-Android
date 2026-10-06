package com.revnix

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * REV-271: paywall localization. A designed paywall carries ONE tree plus a
 * side table of translated strings — never one tree per language — so styles,
 * layout and block ids are shared and only words differ.
 *
 * Selection happens HERE, at render, rather than server-side at resolve. The
 * resolution is cached on the device, so a locale chosen by the server would
 * pin a cached paywall to whatever language it was fetched in: change the
 * phone's language and the old copy would keep rendering until the cache
 * expired, and offline it would never change at all.
 *
 * Mirrored across all six Revnix SDKs — keep the key scheme, the fallback
 * chain and the field list identical.
 */
public data class PaywallLocalization(
    /** The language the tree's own copy is written in; a localized copy names
     *  the language it was localized to. Never a key in a parsed doc's [tables]. */
    public val defaultLocale: String? = null,
    /** BCP-47 tag → (`<blockId>.<path>` → translated string). */
    public val tables: Map<String, Map<String, String>> = emptyMap(),
) {
    public val isEmpty: Boolean get() = tables.isEmpty()

    public companion object {
        /**
         * Reads `defaultLocale` / `locales` off a raw block document. A
         * malformed table costs the TRANSLATIONS, never the paywall — the same
         * forgiveness every other optional field in the parser gets.
         */
        public fun parse(o: JsonObject?): PaywallLocalization {
            if (o == null) return PaywallLocalization()
            val raw = o["locales"] as? JsonObject ?: return PaywallLocalization(
                defaultLocale = o["defaultLocale"]?.localeString,
            )
            val tables = mutableMapOf<String, Map<String, String>>()
            for ((tag, value) in raw) {
                // Normalized on the way IN so a hand-written "es_mx" in the
                // catalog still matches a device reporting "es-MX".
                val normalized = revnixNormalizeLocale(tag) ?: continue
                val table = value as? JsonObject ?: continue
                val strings = mutableMapOf<String, String>()
                for ((key, cell) in table) {
                    strings[key] = cell.localeString ?: continue
                }
                if (strings.isNotEmpty()) tables[normalized] = strings
            }
            return PaywallLocalization(
                defaultLocale = o["defaultLocale"]?.localeString,
                tables = tables,
            )
        }
    }
}

// PaywallBlocks.kt's own `stringOrNull` is file-private; this is the same
// read, kept here so the localization parser has no cross-file dependency on
// a private helper.
private val JsonElement.localeString: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * BCP-47, hyphenated: "es_MX" and "es-mx" both normalize to "es-MX". Applied
 * to authored tags and to the device's own locale alike, so the two can never
 * miss each other over punctuation or case. Null for anything that is not a
 * language tag, which keeps junk out of the lookup instead of into it.
 */
public fun revnixNormalizeLocale(tag: String?): String? {
    if (tag == null) return null
    val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
    val language = parts.firstOrNull()?.lowercase() ?: return null
    if (language.length !in 2..3 || !language.all { it in 'a'..'z' }) return null
    val rest = mutableListOf<String>()
    for (part in parts.drop(1)) {
        // Region subtags are uppercase ("MX", "419"), scripts title case
        // ("Hans"); anything else is a variant and stays lowercase. A subtag
        // outside BCP-47's shapes makes the WHOLE tag junk rather than passing
        // through — otherwise a device reporting "es-!!" would look up a
        // language.
        val value = when {
            part.length == 2 && part.all { it.isAsciiLetter() } -> part.uppercase()
            part.length == 3 && part.all { it in '0'..'9' } -> part
            part.length == 4 && part.all { it.isAsciiLetter() } ->
                part.substring(0, 1).uppercase() + part.substring(1).lowercase()
            part.length in 5..8 && part.all { it.isAsciiLetter() || it in '0'..'9' } ->
                part.lowercase()
            else -> return null
        }
        rest.add(value)
    }
    return (listOf(language) + rest).joinToString("-")
}

@Volatile
private var localeOverride: String? = null

internal fun revnixSetLocaleOverride(tag: String?) {
    localeOverride = tag
}

/**
 * The device's language as a BCP-47 tag. `Locale.getDefault()` is what every
 * other surface in the app localizes to — including the per-app language an
 * Android 13+ user can pick — so a paywall matching it matches the app.
 */
public fun revnixDeviceLocale(): String? {
    localeOverride?.let { return it }
    return runCatching { java.util.Locale.getDefault().toLanguageTag() }.getOrNull()
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun baseLanguage(tag: String): String = tag.substringBefore('-')

/**
 * Which tables to consult, most specific first.
 *
 * "es-MX" on a paywall translated into "es" reads the "es" table: a regional
 * variant that was never authored falls back to its language rather than to
 * the source, which is the difference between a Mexican customer reading
 * Spanish and reading English. The authored tree is the last resort and is
 * deliberately NOT in this chain — the lookup falls through to it.
 */
public fun revnixLocaleChain(
    available: Collection<String>,
    locale: String?,
    defaultLocale: String?,
): List<String> {
    val chain = mutableListOf<String>()
    fun push(tag: String?) {
        if (tag != null && tag in available && tag !in chain) chain.add(tag)
    }
    val wanted = revnixNormalizeLocale(locale)
    if (wanted != null) {
        push(wanted)
        push(baseLanguage(wanted))
        // "es" asked for, only "es-MX" authored: one regional table beats the
        // source language, and the first SORTED match keeps the choice
        // deterministic across devices rather than map-order dependent.
        if (chain.isEmpty()) {
            val language = baseLanguage(wanted)
            push(available.filter { baseLanguage(it) == language }.sorted().firstOrNull())
        }
    }
    push(revnixNormalizeLocale(defaultLocale))
    return chain
}

private fun localizeBlock(block: PaywallBlock, lookup: (String, String) -> String): PaywallBlock =
    when (block) {
        is PaywallBlock.Text -> block.copy(text = lookup("${block.id}.text", block.text))
        is PaywallBlock.Button -> block.copy(label = lookup("${block.id}.label", block.label))
        is PaywallBlock.ListBlock -> block.copy(
            items = block.items.mapIndexed { i, item ->
                item.copy(
                    title = lookup("${block.id}.items.$i.title", item.title),
                    description = item.description?.let {
                        lookup("${block.id}.items.$i.description", it)
                    },
                )
            },
        )
        is PaywallBlock.Products -> block.copy(
            titleTpl = block.titleTpl?.let { lookup("${block.id}.titleTpl", it) },
            priceTpl = block.priceTpl?.let { lookup("${block.id}.priceTpl", it) },
            highlightSub = block.highlightSub?.let { lookup("${block.id}.highlightSub", it) },
            badgeText = block.badgeText?.let { lookup("${block.id}.badgeText", it) },
        )
        is PaywallBlock.Card -> block.copy(
            children = block.children.map { localizeBlock(it, lookup) },
        )
        else -> block
    }

/**
 * Returns the document with every string swapped for [locale]'s.
 *
 * Applied ONCE before rendering rather than at each text node: the renderer
 * then needs no localization awareness at all, and the six SDKs cannot drift
 * on which fields are translatable. Returns the receiver untouched when
 * nothing applies, so an untranslated paywall allocates nothing.
 *
 * `{price}` and the other copy tags survive, because they are resolved AFTER
 * this on the localized string — "Solo {price} al mes" works.
 */
public fun PaywallBlockDoc.localized(locale: String?): PaywallBlockDoc {
    if (localization.isEmpty) return this
    val chain = revnixLocaleChain(localization.tables.keys, locale, localization.defaultLocale)
    if (chain.isEmpty()) return this
    val tables = localization.tables
    val lookup: (String, String) -> String = { key, authored ->
        // An empty translation means "not translated", never "render nothing":
        // a blank CTA is a dead paywall, and export/import round-trips leave
        // empty cells behind for every untouched row.
        chain.firstNotNullOfOrNull { tag -> tables[tag]?.get(key)?.takeIf { it.isNotEmpty() } }
            ?: authored
    }
    return copy(
        localization = localization.copy(defaultLocale = chain[0]),
        blocks = blocks.map { localizeBlock(it, lookup) },
    )
}

private val LINK_LABEL_ALIASES: Map<String, String> = mapOf(
    "iw" to "he", "in" to "id", "no" to "nb", "tl" to "fil",
)

private val LINK_LABELS: Map<String, Triple<String, String, String>> = mapOf(
    "ar" to Triple("استعادة", "الشروط", "الخصوصية"),
    "bg" to Triple("Възстановяване", "Условия", "Поверителност"),
    "bn" to Triple("পুনরুদ্ধার", "শর্তাবলী", "গোপনীয়তা"),
    "ca" to Triple("Restaura", "Condicions", "Privadesa"),
    "cs" to Triple("Obnovit", "Podmínky", "Soukromí"),
    "da" to Triple("Gendan", "Vilkår", "Privatliv"),
    "de" to Triple("Wiederherstellen", "AGB", "Datenschutz"),
    "el" to Triple("Επαναφορά", "Όροι", "Απόρρητο"),
    "en" to Triple("Restore", "Terms", "Privacy"),
    "es" to Triple("Restaurar", "Términos", "Privacidad"),
    "et" to Triple("Taasta", "Tingimused", "Privaatsus"),
    "fa" to Triple("بازیابی", "شرایط", "حریم خصوصی"),
    "fi" to Triple("Palauta", "Ehdot", "Tietosuoja"),
    "fil" to Triple("I-restore", "Mga Tuntunin", "Privacy"),
    "fr" to Triple("Restaurer", "Conditions", "Confidentialité"),
    "he" to Triple("שחזור", "תנאים", "פרטיות"),
    "hi" to Triple("पुनर्स्थापित करें", "शर्तें", "गोपनीयता"),
    "hr" to Triple("Vrati", "Uvjeti", "Privatnost"),
    "hu" to Triple("Visszaállítás", "Feltételek", "Adatvédelem"),
    "id" to Triple("Pulihkan", "Ketentuan", "Privasi"),
    "it" to Triple("Ripristina", "Termini", "Privacy"),
    "ja" to Triple("購入を復元", "利用規約", "プライバシー"),
    "ko" to Triple("구매 복원", "이용약관", "개인정보"),
    "lt" to Triple("Atkurti", "Sąlygos", "Privatumas"),
    "lv" to Triple("Atjaunot", "Noteikumi", "Privātums"),
    "ms" to Triple("Pulihkan", "Terma", "Privasi"),
    "nb" to Triple("Gjenopprett", "Vilkår", "Personvern"),
    "nl" to Triple("Herstellen", "Voorwaarden", "Privacy"),
    "pl" to Triple("Przywróć", "Regulamin", "Prywatność"),
    "pt" to Triple("Restaurar", "Termos", "Privacidade"),
    "ro" to Triple("Restaurează", "Termeni", "Confidențialitate"),
    "ru" to Triple("Восстановить", "Условия", "Конфиденциальность"),
    "sk" to Triple("Obnoviť", "Podmienky", "Súkromie"),
    "sl" to Triple("Obnovi", "Pogoji", "Zasebnost"),
    "sr" to Triple("Врати", "Услови", "Приватност"),
    "sv" to Triple("Återställ", "Villkor", "Integritet"),
    "th" to Triple("กู้คืน", "ข้อกำหนด", "ความเป็นส่วนตัว"),
    "tr" to Triple("Geri Yükle", "Koşullar", "Gizlilik"),
    "uk" to Triple("Відновити", "Умови", "Конфіденційність"),
    "ur" to Triple("بحال کریں", "شرائط", "رازداری"),
    "vi" to Triple("Khôi phục", "Điều khoản", "Quyền riêng tư"),
    "zh" to Triple("恢复购买", "条款", "隐私"),
    "zh-Hant" to Triple("恢復購買", "條款", "隱私"),
)

/**
 * The three built-in paywall footer labels (restore, terms, privacy), in the
 * language [locale] resolves to. Falls back to English for any language not
 * in the 43-entry table. Mandarin picks traditional script for Taiwan, Hong
 * Kong and Macau unless the tag is explicitly simplified.
 */
public fun revnixLinkLabels(locale: String?): Triple<String, String, String> =
    LINK_LABELS[resolveLinkLabelTag(locale)] ?: LINK_LABELS["en"]!!

private fun resolveLinkLabelTag(locale: String?): String {
    val tag = revnixNormalizeLocale(locale) ?: return "en"
    val lang = baseLanguage(tag)
    if (lang == "zh") {
        val parts = tag.split("-").drop(1)
        val hasHant = parts.contains("Hant")
        val hasHans = parts.contains("Hans")
        val region = parts.firstOrNull { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
        val isTraditionalRegion = region != null && region in setOf("TW", "HK", "MO")
        return if (hasHant || (!hasHans && isTraditionalRegion)) "zh-Hant" else "zh"
    }
    val base = LINK_LABEL_ALIASES[lang] ?: lang
    return if (base in LINK_LABELS) base else "en"
}
