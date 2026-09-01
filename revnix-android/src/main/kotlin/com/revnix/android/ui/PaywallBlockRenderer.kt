// PaywallBlockRenderer — draws a designed paywall.
//
// A paywall built in the dashboard's block builder publishes a TREE of styled
// elements on `PaywallConfig.blocks`. `RevnixPaywallView` draws that tree
// through this renderer when it is present, and falls back to the classic
// `template` layouts when it is not — so every paywall published before the
// block builder keeps rendering exactly as it did.
//
// revnix-app's src/components/paywall-blocks/BlockScreen.tsx is the reference
// renderer; keep the two in lockstep. The model and its parser live in the
// core (com.revnix.PaywallBlockDoc), which is plain Kotlin and JVM-testable.
//
// Built from programmatic classic Views, like the rest of this SDK: the
// container layouts map onto LinearLayout (column / row), FrameLayout (stack)
// and GridLayout (grid). Compose would have been the obvious mapping, but this
// SDK is deliberately dependency-free — adding Compose to draw one screen
// would put the whole runtime into every host app. Classic Views still work
// inside a Compose host through `AndroidView` interop.
//
// Two rules matter more here than in the dashboard, because a shipped app
// cannot be patched from our side:
//
//   * An unknown block type, layout or style field is SKIPPED and the rest of
//     the screen still draws. Never crash, never blank.
//   * A tag with no data behind it stays visible (`{price}` draws as
//     `{price}`) rather than resolving to something wrong.

package com.revnix.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.revnix.BlockPackage
import com.revnix.BlockStyle
import com.revnix.PaywallBlock
import com.revnix.PaywallBlockDoc
import com.revnix.RevnixBackgroundLayers
import com.revnix.revnixBackgroundBaseColor
import com.revnix.revnixBackgroundLayers
import com.revnix.revnixBlockColor
import com.revnix.revnixParseCssGradients
import com.revnix.revnixResolveTags
import kotlin.math.roundToInt

/** Everything the tree needs that is not in the document itself. */
internal class BlockContext(
    val doc: PaywallBlockDoc,
    val packages: List<BlockPackage>,
    /**
     * The package a plan card visually emphasizes, and the one whose tags a
     * subtree resolves against outside a `repeat`.
     */
    val selectedPackageId: String?,
    val heroImageUrl: String?,
    val footerTermsUrl: String?,
    val footerPrivacyUrl: String?,
    val onPurchase: (String) -> Unit,
    val onRestore: (() -> Unit)?,
    val onTerms: (() -> Unit)?,
    val onPrivacy: (() -> Unit)?,
)

/**
 * Draws a block document into a container.
 *
 * Every method here answers with a View or null; nothing throws. A block it
 * cannot draw contributes nothing and its siblings are unaffected.
 */
internal class PaywallBlockRenderer(
    private val context: Context,
    private val ctx: BlockContext,
) {
    private val doc = ctx.doc
    private val density = context.resources.displayMetrics.density

    private fun dp(value: Double): Int = (value * density).roundToInt()

    /**
     * The gradient, photo and scrim layers, bottom first. Empty for an
     * unedited paywall whose background is a flat colour, so that case renders
     * exactly as it did before.
     */
    private fun backgroundArt(layers: RevnixBackgroundLayers): List<View> {
        val out = mutableListOf<View>()

        layers.ground?.let { ground ->
            val gradients = revnixParseCssGradients(ground) { revnixBlockColor(it, doc) }
            if (gradients.isNotEmpty()) {
                out.add(View(context).apply { background = RevnixGradientDrawable(gradients) })
            }
        }

        layers.image?.let { out.add(RevnixBackgroundPhotoView(context, it)) }

        layers.overlay?.let { overlay ->
            val gradients = revnixParseCssGradients(overlay.fill) { revnixBlockColor(it, doc) }
            val solid = if (gradients.isEmpty()) revnixBlockColor(overlay.fill, doc) else null
            if (gradients.isNotEmpty() || solid != null) {
                out.add(revnixScrimView(context, solid, gradients, overlay.opacity))
            }
        }

        return out
    }

    /**
     * The whole screen.
     *
     * A `canvas` document is authored against a fixed 393×852 device screen; it
     * is laid out at that size and scaled as a whole, so absolute placement
     * inside `stack` containers stays true at any width. A `flow` document lays
     * out as an ordinary column.
     */
    fun renderScreen(): View {
        val screen = FrameLayout(context)
        val layers = revnixBackgroundLayers(doc.backgroundSpec)
        // The flat colour under everything. A gradient ground resolves to its
        // first stop here, so a form the parser does not understand still
        // shows a colour from the design rather than black.
        revnixBlockColor(revnixBackgroundBaseColor(layers.ground ?: doc.background), doc)
            ?.let { screen.setBackgroundColor(it) }
        for (layer in backgroundArt(layers)) screen.addView(layer, revnixFillParams())

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        for (block in doc.blocks) {
            render(block, null)?.let { body.addView(it) }
        }

        if (doc.layout == "canvas") {
            val canvasWidth = dp(PaywallBlockDoc.CANVAS_WIDTH.toDouble())
            val canvasHeight = dp(PaywallBlockDoc.CANVAS_HEIGHT.toDouble())
            body.layoutParams = FrameLayout.LayoutParams(canvasWidth, canvasHeight)
            body.pivotX = 0f
            body.pivotY = 0f
            screen.addView(body)
            // The container's width is not known until it is measured, so the
            // scale is applied on layout rather than guessed here.
            screen.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val width = right - left
                if (width <= 0) return@addOnLayoutChangeListener
                val scale = width.toFloat() / canvasWidth
                body.scaleX = scale
                body.scaleY = scale
            }
            return screen
        }

        screen.addView(
            body,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return screen
    }

    /** One block, or null when it contributes nothing. */
    private fun render(block: PaywallBlock, pkg: BlockPackage?): View? = when (block) {
        is PaywallBlock.Text -> textView(
            revnixResolveTags(block.text, pkg, ctx.packages), block.style,
        )

        is PaywallBlock.Image -> imageSlot(block)

        is PaywallBlock.ListBlock -> listColumn(block)

        is PaywallBlock.Products -> productsColumn(block)

        is PaywallBlock.Button -> buttonView(block, pkg)

        is PaywallBlock.Links -> linksRow(block)

        is PaywallBlock.Line -> View(context).apply {
            setBackgroundColor(
                revnixBlockColor(block.style?.fill, doc)
                    ?: withAlpha(revnixBlockColor(doc.textColor, doc) ?: Color.WHITE, 0.16),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(block.style?.height?.px ?: 1.0),
            )
            applyStyle(this, block.style, skipBackground = true)
        }

        is PaywallBlock.Spacer -> View(context).apply {
            layoutParams = if (block.flex == true) {
                // Grows to push what follows to the bottom.
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(block.style?.height?.px ?: 16.0),
                )
            }
        }

        is PaywallBlock.Card -> card(block, pkg)

        // A block type from a newer dashboard: skip it, keep the screen.
        is PaywallBlock.Unknown -> null
    }

    // ——— leaves ———

    private fun textView(text: String, style: BlockStyle?, defaultSizeSp: Float = 15f): TextView {
        val view = TextView(context)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, (style?.fontSize ?: defaultSizeSp.toDouble()).toFloat())
        view.setTextColor(
            revnixBlockColor(style?.textColor, doc)
                ?: revnixBlockColor(doc.textColor, doc)
                ?: Color.WHITE,
        )
        val weight = style?.fontWeight ?: 0.0
        view.typeface = when {
            weight >= 600 -> Typeface.DEFAULT_BOLD
            style?.fontStyle == "italic" -> Typeface.defaultFromStyle(Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
        style?.align?.let {
            view.gravity = when (it) {
                "center" -> Gravity.CENTER_HORIZONTAL
                "right" -> Gravity.END
                else -> Gravity.START
            }
        }
        // CSS letter-spacing is em, which is what Android wants too.
        style?.letterSpacing?.let { view.letterSpacing = it.toFloat() }
        style?.lineHeight?.let { view.setLineSpacing(0f, it.toFloat()) }
        if (style?.nowrap == true) {
            view.maxLines = 1
            view.isSingleLine = true
        }
        if (style?.textTransform == "uppercase") view.isAllCaps = true
        if (style?.decoration == "underline") view.paintFlags = view.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        if (style?.decoration == "line-through") {
            view.paintFlags = view.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }
        applyStyle(view, style)
        return view
    }

    private fun imageSlot(block: PaywallBlock.Image): View {
        // Image loading is the host app's job — this SDK ships no image
        // library — so a slot draws as its placeholder box. A design that
        // publishes a URL still reserves the right space for it.
        val style = block.style
        val sized = style != null &&
            (style.inset == true || style.height != null || style.aspectRatio != null || style.flex != null)
        val slot = FrameLayout(context)
        slot.setBackgroundColor(Color.argb(56, 125, 135, 155))
        val label = block.placeholder ?: block.url
        if (!label.isNullOrEmpty()) {
            val text = textView(label, null, defaultSizeSp = 10.5f)
            text.gravity = Gravity.CENTER
            text.alpha = 0.62f
            slot.addView(
                text,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        if (!sized) {
            slot.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160.0),
            )
        }
        applyStyle(slot, style)
        return slot
    }

    private fun listColumn(block: PaywallBlock.ListBlock): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val gap = dp(block.style?.gap ?: 8.0)
        val iconColor = revnixBlockColor(block.iconColor, doc)
            ?: revnixBlockColor(doc.accent, doc)
            ?: Color.WHITE
        for ((index, item) in block.items.withIndex()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val icon = textView(item.icon ?: "✓", null)
            icon.setTextColor(iconColor)
            icon.typeface = Typeface.DEFAULT_BOLD
            row.addView(icon)
            val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val title = textView(item.title, null)
            title.typeface = Typeface.DEFAULT_BOLD
            body.addView(title)
            item.description?.let {
                val description = textView(it, null, defaultSizeSp = 12f)
                description.alpha = 0.7f
                body.addView(description)
            }
            row.addView(
                body,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    marginStart = dp(9.0)
                },
            )
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) topMargin = gap },
            )
        }
        applyStyle(column, block.style)
        return column
    }

    private fun buttonView(block: PaywallBlock.Button, pkg: BlockPackage?): View {
        val accent = revnixBlockColor(doc.accent, doc) ?: Color.BLUE
        val ink = revnixBlockColor(doc.accentInk, doc) ?: Color.WHITE
        val label = textView(revnixResolveTags(block.label, pkg, ctx.packages), block.style)
        label.gravity = Gravity.CENTER
        label.setTextColor(revnixBlockColor(block.style?.textColor, doc) ?: ink)
        label.typeface = Typeface.DEFAULT_BOLD
        val background = GradientDrawable()
        background.setColor(revnixBlockColor(block.style?.fill, doc) ?: accent)
        background.cornerRadius = dp(block.style?.radius ?: 12.0).toFloat()
        label.background = background
        val vertical = if (block.style?.height != null) 0 else dp(15.0)
        label.setPadding(dp(16.0), vertical, dp(16.0), vertical)
        label.isClickable = true
        label.setOnClickListener {
            val id = ctx.selectedPackageId ?: ctx.packages.firstOrNull()?.packageId
            if (id != null) ctx.onPurchase(id)
        }
        return label
    }

    private fun linksRow(block: PaywallBlock.Links): View? {
        // An explicit host handler wins over the config URL — the app knows
        // best how to open its own legal pages; the URL is the fallback.
        val entries = buildList {
            if (block.showRestore != false) add("Restore" to { ctx.onRestore?.invoke(); Unit })
            if (block.showTerms != false) {
                val url = block.termsUrl ?: ctx.footerTermsUrl
                add("Terms" to { open(ctx.onTerms, url) })
            }
            if (block.showPrivacy != false) {
                val url = block.privacyUrl ?: ctx.footerPrivacyUrl
                add("Privacy" to { open(ctx.onPrivacy, url) })
            }
        }
        if (entries.isEmpty()) return null
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        for ((index, entry) in entries.withIndex()) {
            val (label, action) = entry
            val view = textView(label, block.style, defaultSizeSp = 12f)
            view.alpha = 0.65f
            view.isClickable = true
            view.setOnClickListener { action() }
            row.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) marginStart = dp(20.0) },
            )
        }
        applyStyle(row, block.style, skipBackground = true)
        return row
    }

    private fun open(handler: (() -> Unit)?, url: String?) {
        if (handler != null) {
            handler()
            return
        }
        if (url.isNullOrEmpty()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun productsColumn(block: PaywallBlock.Products): View {
        val shown = ctx.packages
        val highlightId = ctx.selectedPackageId?.takeIf { id -> shown.any { it.packageId == id } }
            ?: shown.firstOrNull()?.packageId
        val row = block.direction == "row"
        val container = LinearLayout(context).apply {
            orientation = if (row) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val gap = dp(block.style?.gap ?: 8.0)
        val accent = revnixBlockColor(doc.accent, doc) ?: Color.BLUE

        for ((index, pkg) in shown.withIndex()) {
            val highlighted = pkg.packageId == highlightId
            val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val background = GradientDrawable()
            background.cornerRadius = dp(14.0).toFloat()
            background.setColor(if (highlighted) withAlpha(accent, 0.12) else Color.TRANSPARENT)
            background.setStroke(
                dp(1.5).coerceAtLeast(1),
                if (highlighted) accent else Color.argb(89, 128, 128, 128),
            )
            card.background = background
            card.setPadding(dp(14.0), dp(12.0), dp(14.0), dp(12.0))

            val title = textView(
                withFallback(block.titleTpl ?: "{title}", pkg, pkg.title), null, defaultSizeSp = 14f,
            )
            title.typeface = Typeface.DEFAULT_BOLD
            card.addView(title)
            card.addView(
                textView(
                    withFallback(block.priceTpl ?: "{price}", pkg, pkg.priceLabel), null,
                    defaultSizeSp = 13f,
                ),
            )
            if (highlighted && block.highlightSub != null) {
                val sub = textView(block.highlightSub!!, null, defaultSizeSp = 11.5f)
                sub.alpha = 0.75f
                card.addView(sub)
            }
            if (highlighted && block.badgeText != null) {
                val badge = textView(block.badgeText!!, null, defaultSizeSp = 10f)
                badge.typeface = Typeface.DEFAULT_BOLD
                badge.setTextColor(revnixBlockColor(doc.accentInk, doc) ?: Color.WHITE)
                val badgeBg = GradientDrawable()
                badgeBg.setColor(accent)
                badgeBg.cornerRadius = dp(99.0).toFloat()
                badge.background = badgeBg
                badge.setPadding(dp(8.0), dp(2.0), dp(8.0), dp(2.0))
                card.addView(badge)
            }
            applyStyle(card, if (highlighted) block.highlightStyle else block.cardStyle)

            val params = if (row) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    if (index > 0) marginStart = gap
                }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) topMargin = gap }
            }
            container.addView(card, params)
        }
        applyStyle(container, block.style, skipBackground = true)
        return container
    }

    /**
     * A template that resolves to nothing useful falls back to the plain value
     * — a card must show a title and a price even if its template names a tag
     * this SDK does not know.
     */
    private fun withFallback(tpl: String, pkg: BlockPackage, fallback: String): String {
        val out = revnixResolveTags(tpl, pkg, ctx.packages)
        return out.trim().ifEmpty { fallback }
    }

    // ——— containers ———

    /**
     * The container. `layout` maps onto the platform's own primitives: column
     * → LinearLayout(VERTICAL), row → LinearLayout(HORIZONTAL), stack →
     * FrameLayout, grid → GridLayout. Any value this SDK does not know falls
     * back to a column rather than drawing nothing.
     */
    private fun card(block: PaywallBlock.Card, pkg: BlockPackage?): View? {
        if (block.repeat == "packages") {
            // One designed card, drawn per package. With nothing attached a
            // single instance still draws, so the design stays visible.
            val list: List<BlockPackage?> = ctx.packages.ifEmpty { listOf(null) }
            val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val selected = ctx.selectedPackageId ?: ctx.packages.firstOrNull()?.packageId
            for (each in list) {
                val style = if (each != null && each.packageId == selected) {
                    (block.style ?: BlockStyle()).merging(block.selectedStyle)
                } else {
                    block.style
                }
                wrapper.addView(container(block, each, style))
            }
            return wrapper
        }

        // A card that names a package the offering does not reach is dropped
        // rather than drawn with unresolved tags.
        val index = block.packageIndex
        if (index != null && index >= ctx.packages.size) return null
        val ctxPackage = index?.let { ctx.packages.getOrNull(it) } ?: pkg
        return container(block, ctxPackage, block.style)
    }

    private fun container(
        block: PaywallBlock.Card,
        pkg: BlockPackage?,
        style: BlockStyle?,
    ): View {
        val gap = dp(block.style?.gap ?: 10.0)
        val children = block.children

        val group: ViewGroup = when (block.layout) {
            "stack" -> FrameLayout(context).apply { clipChildren = false }
            "grid" -> GridLayout(context).apply {
                columnCount = gridColumnCount(block)
                useDefaultMargins = false
            }
            "row" -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = rowGravity(block.style)
            }
            // column, and anything unrecognized.
            else -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = columnGravity(block.style)
            }
        }

        for ((index, child) in children.withIndex()) {
            val view = render(child, pkg) ?: continue
            when (group) {
                is FrameLayout -> group.addView(view, stackParams(child.style))
                is GridLayout -> group.addView(
                    view,
                    GridLayout.LayoutParams().apply {
                        width = 0
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(0, if (index >= gridColumnCount(block)) gap else 0, 0, 0)
                    },
                )
                else -> group.addView(view, lineParams(child, index, gap, block.layout == "row"))
            }
        }
        applyStyle(group, style)
        return group
    }

    private fun gridColumnCount(block: PaywallBlock.Card): Int {
        // The design's own track list wins; `columns` is the simple form.
        block.gridColumns?.takeIf { it.isNotBlank() }?.let { return it.trim().split(Regex("\\s+")).size }
        return (block.columns ?: 2).coerceAtLeast(1)
    }

    /** flex-grow, flex-shrink and the gap, as LinearLayout understands them. */
    private fun lineParams(
        child: PaywallBlock,
        index: Int,
        gap: Int,
        horizontal: Boolean,
    ): LinearLayout.LayoutParams {
        val style = child.style
        val grow = style?.flex ?: 0.0
        val basis = style?.basis?.let { dp(it) }
        val explicit = style?.width?.px?.let { dp(it) }
        val height = style?.height?.px?.let { dp(it) }

        val params = if (horizontal) {
            LinearLayout.LayoutParams(
                when {
                    grow > 0 -> 0
                    basis != null -> basis
                    explicit != null -> explicit
                    else -> ViewGroup.LayoutParams.WRAP_CONTENT
                },
                height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        } else {
            LinearLayout.LayoutParams(
                explicit ?: ViewGroup.LayoutParams.MATCH_PARENT,
                if (grow > 0) 0 else height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        if (grow > 0) params.weight = grow.toFloat()
        // shrink 0 stops a row item from being squashed — LinearLayout's
        // closest equivalent is refusing to shrink below its measured size.
        if (style?.shrink == 0.0 && horizontal) params.width = basis ?: explicit ?: ViewGroup.LayoutParams.WRAP_CONTENT
        if (index > 0) {
            if (horizontal) params.marginStart = gap else params.topMargin = gap
        }
        style?.selfAlign?.let { params.gravity = alignToGravity(it, horizontal) }
        return params
    }

    /** Stack placement — `inset` fills the parent, the offsets pin an edge. */
    private fun stackParams(style: BlockStyle?): FrameLayout.LayoutParams {
        if (style?.inset == true) {
            return FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val params = FrameLayout.LayoutParams(
            style?.width?.px?.let { dp(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            style?.height?.px?.let { dp(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        var gravity = 0
        style?.top?.px?.let { params.topMargin = dp(it); gravity = gravity or Gravity.TOP }
        style?.bottom?.px?.let { params.bottomMargin = dp(it); gravity = gravity or Gravity.BOTTOM }
        style?.left?.px?.let { params.marginStart = dp(it); gravity = gravity or Gravity.START }
        style?.right?.px?.let { params.marginEnd = dp(it); gravity = gravity or Gravity.END }
        // A percentage offset has no fixed pixel value; the design's intent
        // there is centring, which is what a stack does by default.
        if (style?.left?.fraction != null || style?.right?.fraction != null) {
            gravity = gravity or Gravity.CENTER_HORIZONTAL
        }
        if (gravity != 0) params.gravity = gravity
        return params
    }

    private fun rowGravity(style: BlockStyle?): Int {
        val main = when (style?.justify) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "end" -> Gravity.END
            else -> Gravity.START
        }
        val cross = when (style?.items) {
            "center" -> Gravity.CENTER_VERTICAL
            "end" -> Gravity.BOTTOM
            "baseline" -> Gravity.TOP
            else -> Gravity.TOP
        }
        return main or cross
    }

    private fun columnGravity(style: BlockStyle?): Int {
        val cross = when (style?.items) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "end" -> Gravity.END
            else -> Gravity.START
        }
        val main = when (style?.justify) {
            "center" -> Gravity.CENTER_VERTICAL
            "end" -> Gravity.BOTTOM
            else -> Gravity.TOP
        }
        return cross or main
    }

    private fun alignToGravity(value: String, horizontal: Boolean): Int = when (value) {
        "center" -> if (horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        "end" -> if (horizontal) Gravity.BOTTOM else Gravity.END
        "start" -> if (horizontal) Gravity.TOP else Gravity.START
        else -> Gravity.NO_GRAVITY
    }

    // ——— style ———

    /**
     * Applies the box half of a style to a view.
     *
     * Every field is read independently and only when present, which is what
     * lets a design authored against a newer dashboard draw here minus the one
     * effect this SDK does not know, rather than failing.
     */
    private fun applyStyle(view: View, style: BlockStyle?, skipBackground: Boolean = false) {
        if (style == null) return

        val paddingLeft = style.paddingLeft ?: style.paddingX ?: style.padding
        val paddingRight = style.paddingRight ?: style.paddingX ?: style.padding
        val paddingTop = style.paddingTop ?: style.paddingY ?: style.padding
        val paddingBottom = style.paddingBottom ?: style.paddingY ?: style.padding
        if (paddingLeft != null || paddingRight != null || paddingTop != null || paddingBottom != null) {
            view.setPadding(
                dp(paddingLeft ?: 0.0), dp(paddingTop ?: 0.0),
                dp(paddingRight ?: 0.0), dp(paddingBottom ?: 0.0),
            )
        }

        if (!skipBackground) {
            val fill = revnixBlockColor(style.fill, doc)
            val borderColor = revnixBlockColor(style.borderColor, doc)
            val radius = style.radius
            if (fill != null || borderColor != null || radius != null) {
                val background = GradientDrawable()
                if (fill != null) background.setColor(fill)
                radius?.let { background.cornerRadius = dp(it).toFloat() }
                if (borderColor != null || style.borderWidth != null) {
                    background.setStroke(
                        dp(style.borderWidth ?: 1.0).coerceAtLeast(1),
                        borderColor ?: revnixBlockColor(doc.textColor, doc) ?: Color.WHITE,
                    )
                }
                view.background = background
            }
        }

        style.opacity?.let { view.alpha = (it / 100).toFloat().coerceIn(0f, 1f) }
        style.rotate?.let { view.rotation = it.toFloat() }
        style.minHeight?.let { view.minimumHeight = dp(it) }
        style.zIndex?.let { view.elevation = it.toFloat() }

        // Margins are only meaningful once the view is in a parent that
        // understands them; the layout params the caller built already carry
        // the gap, so this only adds what the design asked for on top.
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            style.marginTop?.px?.let { params.topMargin = dp(it) }
            style.marginBottom?.px?.let { params.bottomMargin = dp(it) }
            style.marginLeft?.px?.let { params.marginStart = dp(it) }
            style.marginRight?.px?.let { params.marginEnd = dp(it) }
            style.margin?.let {
                val m = dp(it)
                params.setMargins(m, m, m, m)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Double): Int =
        Color.argb(
            (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color),
        )
}
