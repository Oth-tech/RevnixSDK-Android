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
// core (com.revnix.PaywallBlockDoc), which is plain Kotlin and JVM-testable,
// and so does the selection logic (com.revnix.PaywallSelection.kt): which
// package is selected, which style a block takes in that state and whether it
// is drawn at all are pure functions this file only APPLIES. The render
// contract (REV-262) pins those rules across all seven renderers.
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
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.revnix.BlockAction
import com.revnix.BlockPackage
import com.revnix.BlockSelectionContext
import com.revnix.BlockStyle
import com.revnix.PaywallBlock
import com.revnix.PaywallBlockDoc
import com.revnix.RevnixBackgroundLayers
import com.revnix.revnixBackgroundBaseColor
import com.revnix.revnixBackgroundLayers
import com.revnix.revnixBlockColor
import com.revnix.revnixBlockFill
import com.revnix.revnixBlockStrokeColor
import com.revnix.revnixCardContext
import com.revnix.revnixEffectiveStyle
import com.revnix.revnixHasCloseAction
import com.revnix.revnixIsBlockVisible
import com.revnix.revnixPackageContext
import com.revnix.revnixParseCssGradients
import com.revnix.revnixResolveTags
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything the tree needs that is not in the document itself. */
internal class BlockContext(
    val doc: PaywallBlockDoc,
    val packages: List<BlockPackage>,
    /**
     * The selected package — already resolved by the view through
     * `revnixSelectedPackageId` (host selection → tap → highlight → first),
     * so it always names an offered package when there is one. Plan cards
     * emphasize it, and copy outside any plan card resolves its tags against
     * it.
     */
    val selectedPackageId: String?,
    val heroImageUrl: String?,
    val footerTermsUrl: String?,
    val footerPrivacyUrl: String?,
    val onPurchase: (String) -> Unit,
    /**
     * Reports a plan card tap. Selection is the paywall's own state, so a
     * design's plan cards work without the host wiring anything.
     */
    val onSelect: (String) -> Unit,
    val onRestore: (() -> Unit)?,
    val onTerms: (() -> Unit)?,
    val onPrivacy: (() -> Unit)?,
    /**
     * Dismissal (REV-252). Null means the host wired none, and no close is
     * drawn at all — a dead close button is worse than none.
     */
    val onClose: (() -> Unit)? = null,
    /**
     * The host's `loading` flag. Purchase buttons draw a spinner in place of
     * their label and ignore taps while it is set; close buttons are
     * unaffected. Swallowing the tap with no visual was the bug (REV-262).
     */
    val loading: Boolean = false,
    /**
     * Where the renderer reports a paint string it could not read. Local only —
     * it never leaves the device. The screen still draws (a fill falls back to
     * a colour from the design), so this is the only way a host learns that a
     * paywall is rendering approximately.
     */
    val onDiagnostic: ((String) -> Unit)? = null,
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
    private val selectedPackage: BlockPackage? =
        ctx.packages.firstOrNull { it.packageId == ctx.selectedPackageId }

    private fun dp(value: Double): Int = (value * density).roundToInt()

    /**
     * A drawn block and the style it was drawn with. The parent needs the
     * style too — it is what sizes and places the child — and it is the
     * EFFECTIVE style (with `selectedStyle` merged in selected context), which
     * only the child's own render knows.
     */
    private class Drawn(val view: View, val style: BlockStyle?)

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
     * A `canvas` document is authored against a fixed 393×852 device screen.
     * It is laid out at 393 design units wide and scaled uniformly by the
     * viewport width (capped at 480dp, so a phone design never balloons on a
     * tablet), centred, with the document background filling the whole
     * viewport around it. Its height stretches to fill a taller viewport and
     * SCROLLS on a shorter one — never a band, never a clipped CTA. A `flow`
     * document lays out as an ordinary scrolling column.
     */
    fun renderScreen(): View {
        val screen = InsetRequestingFrame(context)
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
        var placed = 0
        for (block in doc.blocks) {
            val drawn = render(block, BlockSelectionContext.NONE) ?: continue
            body.addView(drawn.view, lineParams(block, drawn.style, placed++, gap = 0, horizontal = false))
        }

        // Scroll indicators hidden and no bounce while the content fits, on
        // both layouts. `OVER_SCROLL_IF_CONTENT_SCROLLS` is exactly that rule.
        val scroll = CanvasScroll(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipChildren = false
            clipToPadding = false
        }
        if (doc.layout == "canvas") {
            val stage = CanvasStage(
                context, body,
                canvasWidth = dp(PaywallBlockDoc.CANVAS_WIDTH.toDouble()),
                minHeight = dp(PaywallBlockDoc.CANVAS_HEIGHT.toDouble()),
                maxWidth = dp(CANVAS_MAX_WIDTH),
            )
            scroll.stage = stage
            scroll.addView(
                stage,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        } else {
            // fillViewport so a flex spacer can still push the CTA to the
            // bottom of a screen the column does not fill.
            scroll.isFillViewport = true
            scroll.addView(
                body,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        screen.addView(scroll, revnixFillParams())

        // Added AFTER the scroll, and never inside the scaled body, so the
        // fallback close keeps its tap size and its distance from the screen
        // edge whatever the device width does to the design — and sits below
        // the status bar when the host draws edge to edge.
        fallbackClose()?.let { chip ->
            screen.addView(chip)
            screen.setOnApplyWindowInsetsListener { _, insets ->
                val params = chip.layoutParams as FrameLayout.LayoutParams
                params.topMargin = dp(CLOSE_INSET) + statusBarInset(insets)
                chip.layoutParams = params
                insets
            }
        }
        return screen
    }

    private fun statusBarInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.statusBars()).top
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop
        }

    /**
     * The dismiss affordance the renderer supplies itself (REV-252), or null
     * when it should not draw one.
     *
     * Drawn only when the design authors no close of its own AND the host
     * wired an `onClose` — which is what makes every paywall published before
     * close existed dismissible without being re-authored, while a design that
     * DOES carry a close chip never ends up showing two.
     *
     * Deliberately plain: it is a safety net, not a design element. Tinted
     * from the screen's own ink rather than a fixed white, so it stays legible
     * on a light design as well as a dark one.
     */
    private fun fallbackClose(): View? {
        val onClose = ctx.onClose ?: return null
        if (revnixHasCloseAction(doc.blocks)) return null
        val ink = revnixBlockColor(doc.textColor, doc) ?: Color.WHITE
        val glyph = TextView(context).apply {
            text = "×"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ink)
            gravity = Gravity.CENTER
            contentDescription = "Close"
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(ink, 0.14))
            }
            setOnClickListener { onClose() }
        }
        glyph.layoutParams = FrameLayout.LayoutParams(dp(30.0), dp(30.0), Gravity.TOP or Gravity.END)
            .apply { topMargin = dp(CLOSE_INSET); marginEnd = dp(CLOSE_INSET) }
        return glyph
    }

    /**
     * One block in its parent's selected context, or null when it contributes
     * nothing: unknown, hidden in this selection state, or a card pinned past
     * the offering. Cards establish their own context (see [card]); every
     * other block takes its style and visibility from the one it is in, and
     * resolves its copy against that context's package — the SELECTED package
     * when it sits outside any plan card.
     */
    private fun render(block: PaywallBlock, parent: BlockSelectionContext): Drawn? {
        if (block is PaywallBlock.Card) return card(block, parent)
        if (!revnixIsBlockVisible(block, parent)) return null
        val style = revnixEffectiveStyle(block, parent)
        val pkg = parent.tagPackage(selectedPackage)
        val view: View = when (block) {
            is PaywallBlock.Text -> textView(
                revnixResolveTags(block.text, pkg, ctx.packages), style,
            ).also { closeOnTap(it, block.action) }

            is PaywallBlock.Image -> imageSlot(block, style).also { closeOnTap(it, block.action) }

            is PaywallBlock.ListBlock -> listColumn(block, style)

            is PaywallBlock.Products -> productsColumn(block, style)

            is PaywallBlock.Button -> buttonView(block, style, pkg)

            is PaywallBlock.Links -> linksRow(block, style) ?: return null

            is PaywallBlock.Line -> View(context).apply {
                val fill = revnixBlockFill(style?.fill, doc, ctx.onDiagnostic)
                if (fill.gradients.isEmpty()) {
                    setBackgroundColor(
                        fill.color ?: withAlpha(revnixBlockColor(doc.textColor, doc) ?: Color.WHITE, 0.16),
                    )
                } else {
                    background = RevnixGradientDrawable(fill.gradients)
                }
                // The parent sizes the view from the style; a line with no
                // height set is a hairline, not nothing.
                minimumHeight = dp(style?.height?.px ?: 1.0)
                applyStyle(this, style, skipBackground = true)
            }

            is PaywallBlock.Spacer -> View(context).apply {
                if (block.flex != true) minimumHeight = dp(style?.height?.px ?: 16.0)
            }

            // Handled above; listed so the `when` stays exhaustive.
            is PaywallBlock.Card -> return null

            // A block type from a newer dashboard: skip it, keep the screen.
            is PaywallBlock.Unknown -> return null
        }
        return Drawn(view, style)
    }

    // ——— leaves ———

    private fun textView(text: String, style: BlockStyle?, defaultSizeSp: Float = 15f): TextView {
        val view = TextView(context)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, (style?.fontSize ?: defaultSizeSp.toDouble()).toFloat())
        view.setTextColor(
            revnixBlockStrokeColor(style?.textColor, doc, ctx.onDiagnostic)
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

    /**
     * An image block: the photo, loaded through the same cached fetch the
     * background uses, over a placeholder that stays only while nothing has
     * loaded. An empty URL falls back to the config's hero image, then to the
     * placeholder alone. `fit` and `shape` apply; `inset` fills a stack.
     */
    private fun imageSlot(block: PaywallBlock.Image, style: BlockStyle?): View {
        // The 160dp default suits a slot dropped into a flow column; a
        // converted design sizes its own slot (pinned edges, explicit box),
        // and the default must not fight it. Same rule as the dashboard.
        val sized = style != null &&
            (
                style.inset == true || style.height != null || style.aspectRatio != null ||
                    style.flex != null || (style.top != null && style.bottom != null)
                )
        val url = block.url?.takeIf { it.isNotBlank() } ?: ctx.heroImageUrl?.takeIf { it.isNotBlank() }
        val round = block.shape == "circle"

        val ratio = style?.aspectRatio?.ratio
        val slot: FrameLayout = if (ratio != null && ratio > 0) AspectFrame(context, ratio) else FrameLayout(context)
        if (!sized) slot.minimumHeight = dp(160.0)

        val placeholder = FrameLayout(context)
        placeholder.setBackgroundColor(Color.argb(56, 125, 135, 155))
        if (url == null) {
            val text = textView(block.placeholder ?: "image", null, defaultSizeSp = 10.5f)
            text.gravity = Gravity.CENTER
            text.alpha = 0.62f
            placeholder.addView(
                text,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        slot.addView(placeholder, revnixFillParams())

        if (url != null) {
            val image = RevnixBlockImageView(context, url) { placeholder.visibility = View.GONE }
            image.scaleType = if (block.fit == "contain") ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            slot.addView(image, revnixFillParams())
        }

        // The photo clips to the slot's shape: a circle, the design's radius,
        // or the 16dp a bare flow slot gets — the dashboard's `overflow:
        // hidden` on the same box.
        val radius = dp(style?.radius ?: if (sized) 0.0 else 16.0).toFloat()
        if (round || radius > 0f) {
            slot.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (round) {
                        val size = min(view.width, view.height)
                        val left = (view.width - size) / 2
                        val top = (view.height - size) / 2
                        outline.setOval(left, top, left + size, top + size)
                    } else {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
            }
            slot.clipToOutline = true
        }
        applyStyle(slot, style)
        return slot
    }

    private fun listColumn(block: PaywallBlock.ListBlock, style: BlockStyle?): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val gap = dp(style?.gap ?: 8.0)
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
        applyStyle(column, style)
        return column
    }

    /**
     * Makes an element the paywall's dismiss target when the design marks it
     * as one (REV-252). A block with no close action gets no listener at all,
     * so it never intercepts a tap meant for what sits behind it.
     */
    private fun closeOnTap(view: View, action: BlockAction?) {
        val onClose = ctx.onClose
        if (action != BlockAction.Close || onClose == null) return
        view.isClickable = true
        view.contentDescription = "Close"
        // The whole box is the target, not just the glyph: a close chip is
        // mostly padding, and a bare × is well under the 48dp tap minimum.
        view.setOnClickListener { onClose() }
    }

    /**
     * A button: the label centred over the fill, dimmed to 80% while pressed.
     * A purchase button under the host's `loading` flag keeps its size and
     * fill, hides its label, centres a spinner in the accent ink and ignores
     * taps. Close buttons ignore `loading` — the customer can always leave.
     */
    private fun buttonView(block: PaywallBlock.Button, style: BlockStyle?, pkg: BlockPackage?): View {
        val accent = revnixBlockColor(doc.accent, doc) ?: Color.BLUE
        val ink = revnixBlockColor(doc.accentInk, doc) ?: Color.WHITE
        // A button the design marks as the close dismisses instead of buying,
        // and takes no accent fill: the CTA must stay the one accented thing
        // on the screen, or a "Not now" competes with "Subscribe" for the eye.
        val closes = block.action == BlockAction.Close && ctx.onClose != null
        val busy = ctx.loading && !closes

        val label = textView(revnixResolveTags(block.label, pkg, ctx.packages), style)
        // The box half of the style belongs to the button, not its label.
        label.background = null
        label.setPadding(0, 0, 0, 0)
        label.alpha = 1f
        label.rotation = 0f
        label.gravity = Gravity.CENTER
        label.setTextColor(
            revnixBlockStrokeColor(style?.textColor, doc, ctx.onDiagnostic)
                ?: if (closes) (revnixBlockColor(doc.textColor, doc) ?: Color.WHITE) else ink,
        )
        label.typeface = Typeface.DEFAULT_BOLD

        val button = PressableFrame(context)
        // The fill is resolved here rather than by `applyStyle` — which is
        // why a gradient CTA used to flatten to the plain accent.
        val fill = revnixBlockFill(style?.fill, doc, ctx.onDiagnostic)
        val corner = dp(style?.radius ?: 12.0).toFloat()
        button.background = if (fill.gradients.isEmpty()) {
            GradientDrawable().apply {
                // The dashboard hands every button's `fill` to CSS
                // `background`; the close-button rule only decides what happens
                // when the design set NO fill of its own.
                val fallback =
                    if (closes || style?.fill != null) Color.TRANSPARENT else accent
                setColor(fill.color ?: fallback)
                cornerRadius = corner
            }
        } else {
            RevnixGradientDrawable(fill.gradients, cornerRadius = corner)
        }
        applyStyle(button, style, skipBackground = true)
        if (!hasPadding(style)) {
            val vertical = if (style?.height != null) 0 else dp(15.0)
            button.setPadding(dp(16.0), vertical, dp(16.0), vertical)
        }
        button.baseAlpha = button.alpha
        button.addView(
            label,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ),
        )

        if (busy) {
            // INVISIBLE, not GONE, so the button keeps the label's height.
            label.visibility = View.INVISIBLE
            val spinner = ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(ink)
            }
            button.addView(spinner, FrameLayout.LayoutParams(dp(20.0), dp(20.0), Gravity.CENTER))
            button.isClickable = false
            button.isEnabled = false
            return button
        }

        button.isClickable = true
        button.setOnClickListener {
            if (closes) {
                ctx.onClose?.invoke()
            } else {
                val id = ctx.selectedPackageId ?: ctx.packages.firstOrNull()?.packageId
                if (id != null) ctx.onPurchase(id)
            }
        }
        return button
    }

    private fun hasPadding(style: BlockStyle?): Boolean = style != null && (
        style.padding != null || style.paddingX != null || style.paddingY != null ||
            style.paddingTop != null || style.paddingRight != null ||
            style.paddingBottom != null || style.paddingLeft != null
        )

    private fun linksRow(block: PaywallBlock.Links, style: BlockStyle?): View? {
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
            val view = textView(label, style, defaultSizeSp = 12f)
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
        applyStyle(row, style, skipBackground = true)
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

    private fun productsColumn(block: PaywallBlock.Products, style: BlockStyle?): View {
        val shown = ctx.packages
        val highlightId = ctx.selectedPackageId?.takeIf { id -> shown.any { it.packageId == id } }
            ?: shown.firstOrNull()?.packageId
        val row = block.direction == "row"
        val container = LinearLayout(context).apply {
            orientation = if (row) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val gap = dp(style?.gap ?: 8.0)
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
            // The whole card is the target, not just its glyphs — a plan row
            // is mostly padding, and tapping beside the price must select.
            // No pressed dimming: the highlight IS the feedback.
            card.setOnClickListener { ctx.onSelect(pkg.packageId) }

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
        applyStyle(container, style, skipBackground = true)
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
     * A card, in the context it establishes.
     *
     * A `repeat` card draws once per package, each instance in its own
     * package's context. A pinned card (`packageIndex`) starts a context from
     * its package; any other card inherits its parent's. Both kinds of
     * package card double as their package's selection target — that is how
     * hand-styled plan rows become tappable without a products block — and
     * everything inside them, not only the card itself, takes `selectedStyle`
     * and honours `visibility` against that package (REV-262).
     */
    private fun card(block: PaywallBlock.Card, parent: BlockSelectionContext): Drawn? {
        if (block.repeat == "packages") {
            // With nothing attached a single instance still draws, so the
            // design stays visible.
            val list: List<BlockPackage?> = ctx.packages.ifEmpty { listOf(null) }
            val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (each in list) {
                val sc = revnixPackageContext(each, ctx.selectedPackageId)
                if (!revnixIsBlockVisible(block, sc)) continue
                wrapper.addView(container(block, sc, revnixEffectiveStyle(block, sc), selects = each?.packageId))
            }
            return Drawn(wrapper, block.style)
        }

        // A card that names a package the offering does not reach is dropped
        // rather than drawn with unresolved tags.
        val sc = revnixCardContext(block, parent, ctx.packages, ctx.selectedPackageId) ?: return null
        if (!revnixIsBlockVisible(block, sc)) return null
        val style = revnixEffectiveStyle(block, sc)
        val selects = if (block.packageIndex != null) sc.pkg?.packageId else null
        return Drawn(container(block, sc, style, selects), style)
    }

    /**
     * The container. `layout` maps onto the platform's own primitives: column
     * → LinearLayout(VERTICAL), row → LinearLayout(HORIZONTAL), stack →
     * FrameLayout, grid → GridLayout. Any value this SDK does not know falls
     * back to a column rather than drawing nothing.
     */
    private fun container(
        block: PaywallBlock.Card,
        sc: BlockSelectionContext,
        style: BlockStyle?,
        selects: String? = null,
    ): View {
        val gap = dp(style?.gap ?: 10.0)
        val children = block.children

        val group: ViewGroup = when (block.layout) {
            "stack" -> FrameLayout(context).apply { clipChildren = false }
            "grid" -> GridLayout(context).apply {
                columnCount = gridColumnCount(block)
                useDefaultMargins = false
            }
            "row" -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = rowGravity(style)
            }
            // column, and anything unrecognized.
            else -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = columnGravity(style)
            }
        }

        // Counts DRAWN children, so a hidden first child never leaves the
        // gap it would have carried.
        var placed = 0
        for (child in children) {
            val drawn = render(child, sc) ?: continue
            when (group) {
                is FrameLayout -> group.addView(drawn.view, stackParams(drawn.style))
                is GridLayout -> group.addView(
                    drawn.view,
                    GridLayout.LayoutParams().apply {
                        width = 0
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(0, if (placed >= gridColumnCount(block)) gap else 0, 0, 0)
                        applyMargins(this, drawn.style)
                    },
                )
                else -> group.addView(drawn.view, lineParams(child, drawn.style, placed, gap, block.layout == "row"))
            }
            placed++
        }
        applyStyle(group, style)
        if (selects != null) group.setOnClickListener { ctx.onSelect(selects) }
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
        style: BlockStyle?,
        index: Int,
        gap: Int,
        horizontal: Boolean,
    ): LinearLayout.LayoutParams {
        // A flex spacer grows like a `flex: 1` box, whatever its style says.
        val grow = if (child is PaywallBlock.Spacer && child.flex == true) 1.0 else style?.flex ?: 0.0
        val basis = style?.basis?.let { dp(it) }
        val explicit = style?.width?.px?.let { dp(it) }
        val height = style?.height?.px?.let { dp(it) }
        // `height: "100%"` on a root card of a canvas design is what makes it
        // follow a taller viewport instead of leaving a band.
        val fullHeight = style?.height?.fraction == 1.0

        val params = if (horizontal) {
            LinearLayout.LayoutParams(
                when {
                    grow > 0 -> 0
                    basis != null -> basis
                    explicit != null -> explicit
                    else -> ViewGroup.LayoutParams.WRAP_CONTENT
                },
                height ?: if (fullHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        } else {
            LinearLayout.LayoutParams(
                explicit ?: ViewGroup.LayoutParams.MATCH_PARENT,
                when {
                    grow > 0 -> 0
                    height != null -> height
                    fullHeight -> ViewGroup.LayoutParams.MATCH_PARENT
                    else -> ViewGroup.LayoutParams.WRAP_CONTENT
                },
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
        applyMargins(params, style)
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
            style?.width?.px?.let { dp(it) }
                ?: if (style?.width?.fraction == 1.0) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            style?.height?.px?.let { dp(it) }
                ?: if (style?.height?.fraction == 1.0) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
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
        applyMargins(params, style)
        return params
    }

    /**
     * The design's own margins, on top of whatever gap or offset the parent
     * already placed. Lives on the params, which is the only place a margin
     * means anything.
     */
    private fun applyMargins(params: ViewGroup.MarginLayoutParams, style: BlockStyle?) {
        if (style == null) return
        style.margin?.let {
            val m = dp(it)
            params.setMargins(m, m, m, m)
        }
        style.marginTop?.px?.let { params.topMargin = dp(it) }
        style.marginBottom?.px?.let { params.bottomMargin = dp(it) }
        style.marginLeft?.px?.let { params.marginStart = dp(it) }
        style.marginRight?.px?.let { params.marginEnd = dp(it) }
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
     * effect this SDK does not know, rather than failing. Margins are the
     * parent's business — see [applyMargins].
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
            val fill = revnixBlockFill(style.fill, doc, ctx.onDiagnostic)
            val borderColor = revnixBlockStrokeColor(style.borderColor, doc, ctx.onDiagnostic)
            val radius = style.radius
            if (!fill.isNone || borderColor != null || radius != null) {
                val background = GradientDrawable()
                // A gradient's flat base is painted by the layer below instead,
                // or this shape would cover it.
                val flat = fill.color
                if (flat != null && fill.gradients.isEmpty()) background.setColor(flat)
                radius?.let { background.cornerRadius = dp(it).toFloat() }
                if (borderColor != null || style.borderWidth != null) {
                    background.setStroke(
                        dp(style.borderWidth ?: 1.0).coerceAtLeast(1),
                        borderColor ?: revnixBlockColor(doc.textColor, doc) ?: Color.WHITE,
                    )
                }
                // The shape keeps the stroke and the corners and goes ON TOP,
                // so a gradient fill never paints over its own border.
                view.background = if (fill.gradients.isEmpty()) {
                    background
                } else {
                    LayerDrawable(
                        arrayOf(
                            RevnixGradientDrawable(
                                fill.gradients,
                                cornerRadius = dp(radius ?: 0.0).toFloat(),
                            ),
                            background,
                        ),
                    )
                }
            }
        }

        style.opacity?.let { view.alpha = (it / 100).toFloat().coerceIn(0f, 1f) }
        style.rotate?.let { view.rotation = it.toFloat() }
        style.minHeight?.let { view.minimumHeight = dp(it) }
        style.zIndex?.let { view.elevation = it.toFloat() }
    }

    private fun withAlpha(color: Int, alpha: Double): Int =
        Color.argb(
            (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color),
        )

    private companion object {
        /** The widest a canvas design scales to, in dp — the tablet/landscape rule. */
        const val CANVAS_MAX_WIDTH = 480.0

        /** The fallback close chip's distance from the top and trailing edges, in dp. */
        const val CLOSE_INSET = 14.0
    }
}

// ——— canvas plumbing ———

/**
 * Lays the canvas body out at its authored width, scales it uniformly to
 * the viewport and reports the SCALED size — a plain `scaleX` leaves the
 * layout size untouched, which is what clipped short screens and left a
 * band under tall ones. The body's height in design units grows to fill the
 * viewport, so bottom-anchored groups and `height: 100%` roots follow.
 */
private class CanvasStage(
    context: Context,
    private val body: View,
    private val canvasWidth: Int,
    private val minHeight: Int,
    private val maxWidth: Int,
) : ViewGroup(context) {

    /** The scroll container's inner height, set by [CanvasScroll] before measuring. */
    var viewportHeight: Int = 0

    private var scale = 1f
    private var designHeight = minHeight

    init {
        clipChildren = false
        addView(body)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        scale = if (width > 0 && canvasWidth > 0) min(width, maxWidth).toFloat() / canvasWidth else 1f
        val viewport = if (viewportHeight > 0) viewportHeight else MeasureSpec.getSize(heightMeasureSpec)
        designHeight = max(minHeight, ceil(viewport / scale).toInt())
        body.measure(
            MeasureSpec.makeMeasureSpec(canvasWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(designHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, ceil(designHeight * scale).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Centred: the layout rect is unscaled, so the offset is computed
        // from the scaled width and the transform grows from the top-left.
        val left = (((r - l) - canvasWidth * scale) / 2f).roundToInt()
        body.layout(left, 0, left + canvasWidth, designHeight)
        body.pivotX = 0f
        body.pivotY = 0f
        body.scaleX = scale
        body.scaleY = scale
    }
}

/** A ScrollView that tells its [CanvasStage] how tall the viewport is. */
private class CanvasScroll(context: Context) : ScrollView(context) {
    var stage: CanvasStage? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        stage?.viewportHeight = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * The screen root. Window insets are only dispatched when they change, and
 * a designed paywall rebuilds its screen on every selection — so each new
 * root asks for them as it attaches, or the close chip would sit under the
 * status bar until the next rotation.
 */
private class InsetRequestingFrame(context: Context) : FrameLayout(context) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }
}

/** A box that keeps the design's aspect ratio when nothing fixes its height. */
private class AspectFrame(context: Context, private val ratio: Double) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY && width > 0 && ratio > 0) {
            val height = (width / ratio).roundToInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

/** A button box that draws at 80% while the finger is down. */
private class PressableFrame(context: Context) : FrameLayout(context) {
    /** The alpha the design asked for; pressed dims relative to it. */
    var baseAlpha: Float = 1f
        set(value) {
            field = value
            alpha = value
        }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) baseAlpha * PRESSED_ALPHA else baseAlpha
    }

    private companion object {
        const val PRESSED_ALPHA = 0.8f
    }
}

/**
 * An image block's photo. It asks the loader for a decode sized to its own
 * box once it has one (falling back to the display size while it is still
 * unsized), and takes a cached bitmap straight away on attach — which is
 * what keeps a selection rebuild from flashing every photo on the screen.
 */
private class RevnixBlockImageView(
    context: Context,
    private val url: String,
    private val onLoaded: () -> Unit,
) : ImageView(context) {

    private var requested = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RevnixImageLoader.cached(url)?.let { show(it) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null || requested || (w <= 0 && h <= 0)) return
        requested = true
        val metrics = resources.displayMetrics
        RevnixImageLoader.load(
            url,
            if (w > 0) w else metrics.widthPixels,
            if (h > 0) h else metrics.heightPixels,
        ) { show(it) }
    }

    private fun show(bitmap: Bitmap) {
        if (drawable != null) return
        setImageBitmap(bitmap)
        onLoaded()
    }
}
