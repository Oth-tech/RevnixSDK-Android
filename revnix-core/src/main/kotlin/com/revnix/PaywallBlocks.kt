// The paywall block model — a portable description of a designed paywall.
//
// A paywall built in the dashboard's block builder publishes a TREE of styled
// elements on `PaywallConfig.blocks`, and that tree takes precedence over the
// classic `template` layouts. This file is the model and its parser; the
// Android View renderer that draws it lives in revnix-android's
// ui/PaywallBlockRenderer.kt.
//
// Mirrors revnix-app's src/lib/paywall-blocks/types.ts one-for-one. The two
// must stay in lockstep: the dashboard preview and this SDK renderer are two
// interpreters of the SAME document, and a field only one side knows is a
// design that ships looking different from the design that was approved.
//
// It lives in the CORE, not the Android module, because none of it touches
// Android: it is plain Kotlin, it runs on the JVM test task, and a future KMP
// renderer can share it rather than reimplement it.
//
// Parsing is deliberately TOTAL — nothing here throws. A shipped app cannot be
// patched from our side, so a document from a newer dashboard has to parse to
// "the parts this SDK understands": an unknown block type becomes
// [PaywallBlock.Unknown] and is skipped when drawing, leaving the rest of the
// screen intact.

package com.revnix

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A length the design may write either as a number of pixels or as a CSS
 * string ("50%", "auto", "16/9").
 *
 * Kept as both so a percentage survives parsing instead of being dropped for
 * not being a number — a rail sized at 78% of its parent is a real design, and
 * silently discarding it would collapse the box.
 */
public data class BlockDimension(
    /** The value in px, when it is one. */
    public val px: Double? = null,
    /** The raw string, when the design wrote one. */
    public val text: String? = null,
) {
    /** The value as a fraction of the parent, when written as a percentage. */
    public val fraction: Double?
        get() = text?.takeIf { it.endsWith("%") }?.dropLast(1)?.toDoubleOrNull()?.let { it / 100 }

    public val isAuto: Boolean get() = text == "auto"

    /** An aspect ratio, from either a number or a "16/9" string. */
    public val ratio: Double?
        get() {
            px?.let { return if (it > 0) it else null }
            val raw = text ?: return null
            val parts = raw.split("/")
            if (parts.size == 2) {
                val w = parts[0].trim().toDoubleOrNull()
                val h = parts[1].trim().toDoubleOrNull()
                if (w != null && h != null && h != 0.0) return w / h
            }
            return raw.toDoubleOrNull()
        }

    internal companion object {
        fun from(element: JsonElement?): BlockDimension? {
            val primitive = (element as? JsonPrimitive) ?: return null
            primitive.doubleOrNull?.let { return BlockDimension(px = it) }
            val raw = primitive.contentOrNullSafe ?: return null
            if (raw.endsWith("px")) {
                raw.dropLast(2).toDoubleOrNull()?.let { return BlockDimension(px = it, text = raw) }
            }
            return BlockDimension(text = raw)
        }
    }
}

/**
 * Per-block visual style. Everything optional — a block draws sensibly with no
 * style at all. Sizes are px; colors are any hex/rgb string, or a palette token
 * (`@accent`, `@text`, `@bg`, `@accentInk`, optionally with an alpha
 * percentage: `@text/12`).
 */
public data class BlockStyle(
    public val fill: String? = null,
    public val textColor: String? = null,
    /** 0–100, like the dashboard's opacity inputs. */
    public val opacity: Double? = null,
    public val borderColor: String? = null,
    public val borderWidth: Double? = null,
    /** Per-side rules, as a CSS border shorthand ("1px solid @text/12"). */
    public val borderTop: String? = null,
    public val borderRight: String? = null,
    public val borderBottom: String? = null,
    public val borderLeft: String? = null,
    public val radius: Double? = null,
    public val padding: Double? = null,
    public val paddingX: Double? = null,
    public val paddingY: Double? = null,
    public val paddingTop: Double? = null,
    public val paddingRight: Double? = null,
    public val paddingBottom: Double? = null,
    public val paddingLeft: Double? = null,
    public val margin: Double? = null,
    public val marginTop: BlockDimension? = null,
    public val marginRight: BlockDimension? = null,
    public val marginBottom: BlockDimension? = null,
    public val marginLeft: BlockDimension? = null,
    public val fontSize: Double? = null,
    public val fontWeight: Double? = null,
    /**
     * A design font family name. Drawn only when the host app has that font;
     * otherwise the system face is used, so copy never vanishes.
     */
    public val fontFamily: String? = null,
    public val fontStyle: String? = null,
    public val align: String? = null,
    /** In em, like CSS. Converted against the block's font size. */
    public val letterSpacing: Double? = null,
    /** Unitless multiplier, like CSS. */
    public val lineHeight: Double? = null,
    public val textTransform: String? = null,
    public val decoration: String? = null,
    public val nowrap: Boolean? = null,
    /** Gap between a container's children. */
    public val gap: Double? = null,
    public val height: BlockDimension? = null,
    public val minHeight: Double? = null,
    public val width: BlockDimension? = null,
    public val maxWidth: BlockDimension? = null,
    /** Width-to-height ratio; "16/9" strings are parsed. */
    public val aspectRatio: BlockDimension? = null,
    /** flex-grow inside a row/column container. */
    public val flex: Double? = null,
    /** flex-shrink; 0 stops a row item from being squashed. */
    public val shrink: Double? = null,
    /** flex-basis in px. */
    public val basis: Double? = null,
    public val wrap: Boolean? = null,
    public val justify: String? = null,
    public val items: String? = null,
    public val selfAlign: String? = null,
    public val shadow: String? = null,
    public val blur: Double? = null,
    public val rotate: Double? = null,
    /**
     * Placement inside a `stack` container. `inset` fills the stack; the
     * individual offsets pin an edge. Ignored outside a stack.
     */
    public val inset: Boolean? = null,
    public val top: BlockDimension? = null,
    public val right: BlockDimension? = null,
    public val bottom: BlockDimension? = null,
    public val left: BlockDimension? = null,
    public val zIndex: Double? = null,
    public val overflow: String? = null,
) {
    /**
     * Merges another style over this one, field by field — how a plan card's
     * `selectedStyle` is applied on top of its base style.
     */
    public fun merging(other: BlockStyle?): BlockStyle {
        if (other == null) return this
        return BlockStyle(
            fill = other.fill ?: fill,
            textColor = other.textColor ?: textColor,
            opacity = other.opacity ?: opacity,
            borderColor = other.borderColor ?: borderColor,
            borderWidth = other.borderWidth ?: borderWidth,
            borderTop = other.borderTop ?: borderTop,
            borderRight = other.borderRight ?: borderRight,
            borderBottom = other.borderBottom ?: borderBottom,
            borderLeft = other.borderLeft ?: borderLeft,
            radius = other.radius ?: radius,
            padding = other.padding ?: padding,
            paddingX = other.paddingX ?: paddingX,
            paddingY = other.paddingY ?: paddingY,
            paddingTop = other.paddingTop ?: paddingTop,
            paddingRight = other.paddingRight ?: paddingRight,
            paddingBottom = other.paddingBottom ?: paddingBottom,
            paddingLeft = other.paddingLeft ?: paddingLeft,
            margin = other.margin ?: margin,
            marginTop = other.marginTop ?: marginTop,
            marginRight = other.marginRight ?: marginRight,
            marginBottom = other.marginBottom ?: marginBottom,
            marginLeft = other.marginLeft ?: marginLeft,
            fontSize = other.fontSize ?: fontSize,
            fontWeight = other.fontWeight ?: fontWeight,
            fontFamily = other.fontFamily ?: fontFamily,
            fontStyle = other.fontStyle ?: fontStyle,
            align = other.align ?: align,
            letterSpacing = other.letterSpacing ?: letterSpacing,
            lineHeight = other.lineHeight ?: lineHeight,
            textTransform = other.textTransform ?: textTransform,
            decoration = other.decoration ?: decoration,
            nowrap = other.nowrap ?: nowrap,
            gap = other.gap ?: gap,
            height = other.height ?: height,
            minHeight = other.minHeight ?: minHeight,
            width = other.width ?: width,
            maxWidth = other.maxWidth ?: maxWidth,
            aspectRatio = other.aspectRatio ?: aspectRatio,
            flex = other.flex ?: flex,
            shrink = other.shrink ?: shrink,
            basis = other.basis ?: basis,
            wrap = other.wrap ?: wrap,
            justify = other.justify ?: justify,
            items = other.items ?: items,
            selfAlign = other.selfAlign ?: selfAlign,
            shadow = other.shadow ?: shadow,
            blur = other.blur ?: blur,
            rotate = other.rotate ?: rotate,
            inset = other.inset ?: inset,
            top = other.top ?: top,
            right = other.right ?: right,
            bottom = other.bottom ?: bottom,
            left = other.left ?: left,
            zIndex = other.zIndex ?: zIndex,
            overflow = other.overflow ?: overflow,
        )
    }

    internal companion object {
        fun from(element: JsonElement?): BlockStyle? {
            val o = element as? JsonObject ?: return null
            fun s(key: String) = o[key]?.stringOrNull
            fun d(key: String) = o[key]?.doubleOrNullSafe
            fun b(key: String) = o[key]?.booleanOrNullSafe
            fun dim(key: String) = BlockDimension.from(o[key])
            return BlockStyle(
                fill = s("fill"), textColor = s("textColor"), opacity = d("opacity"),
                borderColor = s("borderColor"), borderWidth = d("borderWidth"),
                borderTop = s("borderTop"), borderRight = s("borderRight"),
                borderBottom = s("borderBottom"), borderLeft = s("borderLeft"),
                radius = d("radius"), padding = d("padding"),
                paddingX = d("paddingX"), paddingY = d("paddingY"),
                paddingTop = d("paddingTop"), paddingRight = d("paddingRight"),
                paddingBottom = d("paddingBottom"), paddingLeft = d("paddingLeft"),
                margin = d("margin"), marginTop = dim("marginTop"), marginRight = dim("marginRight"),
                marginBottom = dim("marginBottom"), marginLeft = dim("marginLeft"),
                fontSize = d("fontSize"), fontWeight = d("fontWeight"),
                fontFamily = s("fontFamily"), fontStyle = s("fontStyle"), align = s("align"),
                letterSpacing = d("letterSpacing"), lineHeight = d("lineHeight"),
                textTransform = s("textTransform"), decoration = s("decoration"),
                nowrap = b("nowrap"), gap = d("gap"), height = dim("height"),
                minHeight = d("minHeight"), width = dim("width"), maxWidth = dim("maxWidth"),
                aspectRatio = dim("aspectRatio"), flex = d("flex"), shrink = d("shrink"),
                basis = d("basis"), wrap = b("wrap"), justify = s("justify"),
                items = s("items"), selfAlign = s("selfAlign"), shadow = s("shadow"),
                blur = d("blur"), rotate = d("rotate"), inset = b("inset"),
                top = dim("top"), right = dim("right"), bottom = dim("bottom"), left = dim("left"),
                zIndex = d("zIndex"), overflow = s("overflow"),
            )
        }
    }
}

/** One entry of a [PaywallBlock.ListBlock]. */
public data class BlockListItem(
    public val icon: String? = null,
    public val title: String,
    public val description: String? = null,
)

/**
 * One node of the tree.
 *
 * [Unknown] is the whole point of this being a sealed hierarchy with a
 * catch-all: a block type introduced after this SDK shipped parses to
 * [Unknown] and is skipped when drawing, so the screen loses that one element
 * rather than failing to render.
 */
public sealed class PaywallBlock {
    public abstract val id: String
    public abstract val style: BlockStyle?

    public data class Text(
        override val id: String,
        public val text: String,
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    public data class Image(
        override val id: String,
        /** Empty falls back to the config's hero image, then to a blank slot. */
        public val url: String? = null,
        public val shape: String? = null,
        public val fit: String? = null,
        public val placeholder: String? = null,
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    public data class ListBlock(
        override val id: String,
        public val items: List<BlockListItem> = emptyList(),
        /** Icon color; defaults to the screen accent. */
        public val iconColor: String? = null,
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    /** Renders the attached offering's packages as selectable cards. */
    public data class Products(
        override val id: String,
        public val direction: String? = null,
        public val titleTpl: String? = null,
        public val priceTpl: String? = null,
        public val highlightSub: String? = null,
        public val badgeText: String? = null,
        public val cardStyle: BlockStyle? = null,
        public val highlightStyle: BlockStyle? = null,
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    public data class Button(
        override val id: String,
        public val label: String,
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    public data class Links(
        override val id: String,
        public val showRestore: Boolean? = null,
        public val showTerms: Boolean? = null,
        public val showPrivacy: Boolean? = null,
        public val termsUrl: String? = null,
        public val privacyUrl: String? = null,
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    public data class Line(
        override val id: String,
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    public data class Spacer(
        override val id: String,
        /** Grows to push what follows to the bottom. */
        public val flex: Boolean? = null,
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    /**
     * The one container block. `layout` picks how children are placed: column
     * / row are flex lines, `stack` layers them (children position with
     * style.inset or the edge offsets), and `grid` is an N-column grid.
     */
    public data class Card(
        override val id: String,
        public val layout: String? = null,
        /** Renders this container once per package in the attached offering. */
        public val repeat: String? = null,
        /** Merged over `style` on the package the customer has selected. */
        public val selectedStyle: BlockStyle? = null,
        /**
         * "This card describes package N of the offering". A card whose index
         * the offering does not reach is hidden.
         */
        public val packageIndex: Int? = null,
        /** grid only; defaults to 2. */
        public val columns: Int? = null,
        /** grid only — a CSS track list ("1fr 60px 66px"). */
        public val gridColumns: String? = null,
        public val children: List<PaywallBlock> = emptyList(),
        override val style: BlockStyle? = null,
    ) : PaywallBlock()

    /** A block type this SDK does not know. Skipped when drawing. */
    public data class Unknown(override val id: String = "") : PaywallBlock() {
        override val style: BlockStyle? get() = null
    }
}

/**
 * The published document: screen palette plus the block tree.
 *
 * Build one with [parse], which never throws: a `null` result means "this is
 * not a designed paywall", and the caller falls back to the classic layouts.
 */
public data class PaywallBlockDoc(
    public val version: Int,
    /**
     * "canvas" designs are authored against a fixed device screen and scale as
     * a whole; "flow" designs lay out in a scrolling column.
     */
    public val layout: String?,
    public val background: String,
    public val textColor: String,
    public val accent: String,
    public val accentInk: String,
    public val fontFamily: String?,
    public val blocks: List<PaywallBlock>,
) {
    public companion object {
        /** The device screen `canvas` designs are authored against. */
        public const val CANVAS_WIDTH: Int = 393
        public const val CANVAS_HEIGHT: Int = 852

        /**
         * Turns the raw `config.blocks` into a document, or null when it is not
         * one. Never throws — a malformed tree costs the DESIGN, and the caller
         * still shows the classic paywall the customer can buy from.
         */
        public fun parse(element: JsonElement?): PaywallBlockDoc? {
            val o = element as? JsonObject ?: return null
            val rawBlocks = (o["blocks"] as? JsonArray) ?: return null
            if (rawBlocks.isEmpty()) return null
            val blocks = rawBlocks.map { parseBlock(it) }
            // `background` is a plain string in the original form and an object
            // in the layered one; both reduce to the ground colour drawn here.
            val background = o["background"]?.stringOrNull
                ?: (o["background"] as? JsonObject)?.get("ground")?.stringOrNull
                ?: "#000000"
            return PaywallBlockDoc(
                version = o["version"]?.intOrNullSafe ?: 1,
                layout = o["layout"]?.stringOrNull,
                background = background,
                textColor = o["textColor"]?.stringOrNull ?: "#FFFFFF",
                accent = o["accent"]?.stringOrNull ?: "#6478ff",
                accentInk = o["accentInk"]?.stringOrNull ?: "#FFFFFF",
                fontFamily = o["fontFamily"]?.stringOrNull,
                blocks = blocks,
            )
        }

        private fun parseBlock(element: JsonElement): PaywallBlock {
            val o = element as? JsonObject ?: return PaywallBlock.Unknown()
            val id = o["id"]?.stringOrNull ?: ""
            val style = BlockStyle.from(o["style"])
            fun s(key: String) = o[key]?.stringOrNull
            fun b(key: String) = o[key]?.booleanOrNullSafe
            return when (o["type"]?.stringOrNull) {
                "text" -> PaywallBlock.Text(id, s("text") ?: "", style)
                "image" -> PaywallBlock.Image(
                    id, s("url"), s("shape"), s("fit"), s("placeholder"), style,
                )
                "list" -> PaywallBlock.ListBlock(
                    id,
                    (o["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                        val io = item as? JsonObject ?: return@mapNotNull null
                        BlockListItem(
                            icon = io["icon"]?.stringOrNull,
                            title = io["title"]?.stringOrNull ?: "",
                            description = io["description"]?.stringOrNull,
                        )
                    },
                    s("iconColor"), style,
                )
                "products" -> PaywallBlock.Products(
                    id, s("direction"), s("titleTpl"), s("priceTpl"), s("highlightSub"),
                    s("badgeText"), BlockStyle.from(o["cardStyle"]),
                    BlockStyle.from(o["highlightStyle"]), style,
                )
                "button" -> PaywallBlock.Button(id, s("label") ?: "", style)
                "links" -> PaywallBlock.Links(
                    id, b("showRestore"), b("showTerms"), b("showPrivacy"),
                    s("termsUrl"), s("privacyUrl"), style,
                )
                "line" -> PaywallBlock.Line(id, style)
                "spacer" -> PaywallBlock.Spacer(id, b("flex"), style)
                "card" -> PaywallBlock.Card(
                    id = id,
                    layout = s("layout"),
                    repeat = s("repeat"),
                    selectedStyle = BlockStyle.from(o["selectedStyle"]),
                    packageIndex = o["packageIndex"]?.intOrNullSafe,
                    columns = o["columns"]?.intOrNullSafe,
                    gridColumns = s("gridColumns"),
                    children = (o["children"] as? JsonArray).orEmpty().map { parseBlock(it) },
                    style = style,
                )
                // A block type from a newer dashboard. Skipped when drawing.
                else -> PaywallBlock.Unknown(id)
            }
        }
    }
}

// ——— tag variables ———

/**
 * One purchasable row, as the block renderer needs it.
 *
 * The money fields are optional: without them the price tags stay visible
 * rather than resolving to a number the store would not charge.
 */
public data class BlockPackage(
    public val packageId: String,
    public val title: String,
    public val priceLabel: String,
    /** Renewal cycle from the product ("annual", "monthly", …). */
    public val period: String? = null,
    public val amountMinor: Long? = null,
    public val currency: String? = null,
)

/** Renewal cycles, in months. Lifetime and one-time products have no cycle. */
private val MONTHS: Map<String, Double> = mapOf(
    "weekly" to 1 / 4.345,
    "monthly" to 1.0,
    "two_months" to 2.0,
    "three_months" to 3.0,
    "six_months" to 6.0,
    "annual" to 12.0,
)

private val PERIOD_WORD: Map<String, String> = mapOf(
    "weekly" to "week", "monthly" to "month", "two_months" to "2 months",
    "three_months" to "3 months", "six_months" to "6 months", "annual" to "year",
    "lifetime" to "lifetime",
)

private val PERIOD_SHORT: Map<String, String> = mapOf(
    "weekly" to "wk", "monthly" to "mo", "two_months" to "2mo",
    "three_months" to "3mo", "six_months" to "6mo", "annual" to "yr",
    "lifetime" to "once",
)

/**
 * Minor units per major unit.
 *
 * Not every currency is a hundredth: JPY and KRW have no minor unit at all, so
 * dividing by 100 would understate a price by 100×. Ask java.util.Currency for
 * the exponent rather than assume one.
 */
public fun revnixMinorUnits(currency: String): Double = runCatching {
    val digits = java.util.Currency.getInstance(currency).defaultFractionDigits
    10.0.pow(if (digits < 0) 2 else digits)
}.getOrDefault(100.0)

private fun money(amountMinor: Double, currency: String): String {
    val per = revnixMinorUnits(currency)
    val major = amountMinor / per
    return runCatching {
        val format = java.text.NumberFormat.getCurrencyInstance()
        format.currency = java.util.Currency.getInstance(currency)
        // A whole amount reads better without ".00"; a fractional one keeps it.
        if (abs(amountMinor % per) < 1e-9) format.maximumFractionDigits = 0
        format.format(major)
    }.getOrElse { "$major $currency" }
}

private fun perMonthMinor(pkg: BlockPackage): Double? {
    val months = pkg.period?.let { MONTHS[it] } ?: return null
    val amount = pkg.amountMinor ?: return null
    if (months <= 0) return null
    return amount / months
}

/**
 * Fills a copy template from one package. [all] is the rest of the offering,
 * which `{save_percent}` needs to have something to compare against.
 *
 * A tag this cannot answer from real package data is left in place, VISIBLE.
 * That is deliberate: a design that says `{price}` and renders a stale sample
 * is worse than one that visibly did not resolve.
 */
public fun revnixResolveTags(
    text: String,
    pkg: BlockPackage?,
    all: List<BlockPackage> = emptyList(),
): String {
    if (pkg == null || !text.contains("{")) return text
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val open = text.indexOf('{', i)
        if (open < 0) {
            out.append(text, i, text.length)
            break
        }
        val close = text.indexOf('}', open)
        if (close < 0) {
            // An unclosed brace is copy, not a tag — leave the rest alone.
            out.append(text, i, text.length)
            break
        }
        out.append(text, i, open)
        val name = text.substring(open + 1, close)
        out.append(resolveOne(name, "{$name}", pkg, all))
        i = close + 1
    }
    return out.toString()
}

private fun resolveOne(
    name: String,
    raw: String,
    pkg: BlockPackage,
    all: List<BlockPackage>,
): String = when (name) {
    "title" -> pkg.title
    "price" -> pkg.priceLabel
    "period" -> pkg.period?.let { PERIOD_WORD[it] } ?: raw
    "period_short" -> pkg.period?.let { PERIOD_SHORT[it] } ?: raw
    "price_per_month" -> {
        val per = perMonthMinor(pkg)
        val currency = pkg.currency
        if (per != null && currency != null) money(per.roundToInt().toDouble(), currency) else raw
    }
    "save_percent" -> {
        val mine = perMonthMinor(pkg)
        val dearest = all.mapNotNull { perMonthMinor(it) }.maxOrNull() ?: 0.0
        if (mine == null || mine <= 0 || dearest <= mine) raw
        else "${((1 - mine / dearest) * 100).roundToInt()}%"
    }
    // Unknown tag: leave it visible rather than guess.
    else -> raw
}

// ——— colors ———

/**
 * Expands a palette token (`@accent`, `@text/12`) and parses the result into an
 * ARGB int. Returns null for anything it cannot parse — a gradient, a named
 * colour — so the caller keeps its own default rather than painting a wrong one.
 */
public fun revnixBlockColor(value: String?, doc: PaywallBlockDoc): Int? {
    var raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    var alpha = 1.0
    if (raw.startsWith("@")) {
        val body = raw.substring(1)
        val slash = body.indexOf('/')
        val name = if (slash >= 0) body.substring(0, slash) else body
        if (slash >= 0) {
            body.substring(slash + 1).toDoubleOrNull()?.let { alpha = (it.coerceIn(0.0, 100.0)) / 100 }
        }
        raw = when (name) {
            "accent" -> doc.accent
            "accentInk" -> doc.accentInk
            "bg" -> doc.background
            "text" -> doc.textColor
            else -> return null
        }
    }
    val base = parseColor(raw) ?: return null
    if (alpha == 1.0) return base
    val a = ((base ushr 24) and 0xFF) * alpha
    return (a.roundToInt().coerceIn(0, 255) shl 24) or (base and 0x00FFFFFF)
}

/** Parses "#rgb", "#rrggbb", "#rrggbbaa", "rgb()" and "rgba()" into ARGB. */
internal fun parseColor(value: String): Int? {
    val s = value.trim()
    if (s.startsWith("#")) {
        var hex = s.substring(1)
        if (hex.length == 3 || hex.length == 4) hex = hex.map { "$it$it" }.joinToString("")
        if (hex.length != 6 && hex.length != 8) return null
        val v = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) {
            (0xFF000000L or v).toInt()
        } else {
            // CSS is #rrggbbaa; Android wants AARRGGBB.
            val rgb = (v ushr 8) and 0xFFFFFF
            val a = v and 0xFF
            ((a shl 24) or rgb).toInt()
        }
    }
    if (!s.lowercase().startsWith("rgb")) return null
    val open = s.indexOf('(')
    val close = s.indexOf(')')
    if (open < 0 || close < 0) return null
    val parts = s.substring(open + 1, close)
        .split(',', '/', ' ')
        .filter { it.isNotBlank() }
        .mapNotNull { it.trim().toDoubleOrNull() }
    if (parts.size < 3) return null
    val a = if (parts.size > 3) (parts[3] * 255).roundToInt() else 255
    return (a.coerceIn(0, 255) shl 24) or
        (parts[0].roundToInt().coerceIn(0, 255) shl 16) or
        (parts[1].roundToInt().coerceIn(0, 255) shl 8) or
        parts[2].roundToInt().coerceIn(0, 255)
}

// ——— JSON helpers ———
//
// Every one of these answers "null" rather than throwing, which is what makes
// the parser above total.

private val JsonPrimitive.contentOrNullSafe: String?
    get() = if (this is JsonPrimitive && isString) content else contentOrNullFallback

private val JsonPrimitive.contentOrNullFallback: String?
    get() = runCatching { content }.getOrNull()

private val JsonElement.stringOrNull: String?
    get() = runCatching { jsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content

private val JsonElement.doubleOrNullSafe: Double?
    get() = runCatching { jsonPrimitive.doubleOrNull }.getOrNull()

private val JsonElement.intOrNullSafe: Int?
    get() = runCatching { jsonPrimitive.intOrNull }.getOrNull()

private val JsonElement.booleanOrNullSafe: Boolean?
    get() = runCatching { jsonPrimitive.booleanOrNull }.getOrNull()

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

@Suppress("unused")
private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

@Suppress("unused")
private fun JsonElement.asArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
