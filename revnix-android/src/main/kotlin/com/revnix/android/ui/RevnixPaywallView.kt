// RevnixPaywallView — renders a published PaywallConfig exactly as the
// dashboard paywall-builder previews it (PaywallPhonePreview.tsx in revnix-app
// is the reference renderer; revnix-sdk's src/ui/RevnixPaywall.tsx is the
// React Native port — keep all three in lockstep, block for block and value
// for value). The config decides template, copy, accent, badge, highlight,
// and hero image; the app supplies package titles/prices (from Play Billing)
// and the purchase handlers, so the display never disagrees with the charge.
//
// `template` is a LAYOUT id. "focus" | "feature-list" | "minimal" are the
// original three and render byte-identically to the RN renderer; the newer
// layouts (hero, timeline, plans, feature-grid, offer, reveal) are distinct
// screen structures the dashboard's template gallery presets over. An
// unrecognized template (config published by a newer dashboard) falls back to
// the classic structure instead of rendering nothing.
//
// Built entirely from programmatic classic Views (FrameLayout / ScrollView /
// LinearLayout / TextView / ImageView / ProgressBar + GradientDrawable) — no
// XML layouts, no Compose, no image library — so the SDK stays
// dependency-free. Classic Views still work inside Compose hosts via
// `AndroidView` interop.

package com.revnix.android.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.revnix.BlockPackage
import com.revnix.PaywallBlockDoc
import com.revnix.PaywallConfig
import com.revnix.RevnixClient
import com.revnix.revnixBackgroundBaseColor
import com.revnix.revnixBlockColor
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One purchasable row. [priceLabel] must come from the store (localized). */
public data class RevnixPaywallPackage(
    val packageId: String,
    val title: String,
    val priceLabel: String,
    /**
     * Renewal cycle from the product ("annual", "monthly", "weekly", …).
     * Drives the `{period}` / `{period_short}` tags on a designed paywall;
     * absent for lifetime and one-time products.
     */
    val period: String? = null,
    /**
     * The store's price in MINOR units, with its currency — what
     * `{price_per_month}` and `{save_percent}` are computed from. Omit them
     * and those tags stay visible rather than resolving to a wrong number; see
     * [com.revnix.revnixMinorUnits] before converting from major units.
     */
    val amountMinor: Long? = null,
    val currency: String? = null,
)

/**
 * Colors the paywall renders with, as ARGB color ints (`Color.parseColor`
 * style). [DEFAULT] mirrors the dashboard preview chrome (a fixed dark
 * screen); [LIGHT] is the base when the config sets `mode: "light"`. Kept in
 * lockstep with the builder preview (PaywallPhonePreview SCREEN_PALETTES).
 */
public data class RevnixPaywallTheme(
    val background: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textFaint: Int,
    val border: Int,
    /** Text color on accent-filled surfaces (CTA, badge). */
    val accentInk: Int,
) {
    public companion object {
        /** Base theme for `mode: "dark"` and legacy configs without `mode`. */
        public val DEFAULT: RevnixPaywallTheme = RevnixPaywallTheme(
            background = 0xFF0F1116.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFF9AA0A8.toInt(),
            textFaint = 0xFF6B7078.toInt(),
            border = 0xFF2A2E36.toInt(),
            accentInk = 0xFF0A0B0D.toInt(),
        )

        /** Base theme when the dashboard config sets `mode: "light"`. */
        public val LIGHT: RevnixPaywallTheme = RevnixPaywallTheme(
            background = 0xFFFFFFFF.toInt(),
            textPrimary = 0xFF16181D.toInt(),
            textSecondary = 0xFF5B6068.toInt(),
            textFaint = 0xFF9AA0A8.toInt(),
            border = 0xFFE2E5EA.toInt(),
            accentInk = 0xFFFFFFFF.toInt(),
        )
    }
}

/**
 * Partial theme override — the RN prop is `Partial<RevnixPaywallTheme>`, same
 * semantics here: null fields inherit the base scheme the config's `mode`
 * selected, so a host can restyle one surface without restating the palette.
 */
public data class RevnixPaywallThemeOverride(
    val background: Int? = null,
    val textPrimary: Int? = null,
    val textSecondary: Int? = null,
    val textFaint: Int? = null,
    val border: Int? = null,
    val accentInk: Int? = null,
)

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Accent used when the config carries none (or an unparseable value). */
private val DEFAULT_ACCENT: Int = 0xFF6478FF.toInt()

// Currency symbols the anchor-price guard recognizes (majors; a symbol-less
// anchor can't be judged and renders as entered).
private val CURRENCY_SYMBOL = Regex("[\$€£¥₹₩₽₺₫₪฿₴₦₱]")

// Soft card surface used by the feature-grid / reveal / review blocks. Not
// part of the public theme — derived from the config's mode, in lockstep
// with the dashboard preview's SCREEN_PALETTES.card.
private val CARD_BG_DARK: Int = 0xFF181B22.toInt()
private val CARD_BG_LIGHT: Int = 0xFFF4F5F7.toInt()

/**
 * Remote paywall renderer — the Android port of revnix-react's
 * `RevnixPaywall` (see the file header for the lockstep rule).
 *
 * ```kotlin
 * val paywall = RevnixPaywallView(context)
 * paywall.bind(
 *     config = resolution.paywall!!.config,
 *     packages = listOf(RevnixPaywallPackage("monthly", "Monthly", "$9.99/mo")),
 *     onPurchase = { packageId -> /* launch the billing flow */ },
 * )
 * container.addView(paywall)
 * ```
 *
 * The view is a plain [FrameLayout]: give it the bounds the paywall should
 * fill. Content is capped at a readable 440dp column, vertically centered on
 * tall screens, and scrolls when it overflows. In a Compose host wrap it in
 * `AndroidView(factory = { RevnixPaywallView(it).apply { bind(…) } })`.
 *
 * Selection is internal by default (initially the config's highlight package,
 * else the first); set [selectedPackageId] to drive it from the host
 * (controlled mode, exactly like the RN prop). [loading] swaps the CTA label
 * for a spinner and blocks purchasing.
 */
public class RevnixPaywallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    // ——— bound state ———
    private var config: PaywallConfig? = null
    private var packages: List<RevnixPaywallPackage> = emptyList()
    private var onPurchase: ((String) -> Unit)? = null
    private var onSelectPackage: ((String) -> Unit)? = null
    private var onRestore: (() -> Unit)? = null
    private var onTerms: (() -> Unit)? = null
    private var onPrivacy: (() -> Unit)? = null
    private var onClose: (() -> Unit)? = null
    private var themeOverride: RevnixPaywallThemeOverride? = null

    // ——— REV-252: this display's close reporting ———
    /** The client bind() was given, kept so a dismissal can report itself. */
    private var reportingClient: RevnixClient? = null
    /** The same client, kept even when view tracking is off: a render
     *  diagnostic is not a beacon, and opting out of analytics should not
     *  also silence "this paywall is drawing approximately". */
    private var diagnosticClient: RevnixClient? = null
    /** The in-flight view beacon (REV-252). The close AWAITS this rather than
     *  reading an id off a field, which fixes two things at once: the id only
     *  exists once the request returns, so a fast dismissal would otherwise
     *  lose the pairing; and a PREVIOUS bind's beacon can no longer land after
     *  a rebind and stamp a stale id over the new display's. Each bind owns
     *  its own Deferred, so a close can only ever read its own display's. */
    private var viewReport: Deferred<String?>? = null
    private var reportPlacementKey: String? = null
    private var reportPaywallId: String? = null

    /** The packages the active template actually shows ("minimal" filters). */
    private var shownPackages: List<RevnixPaywallPackage> = emptyList()
    private var internalSelected: String? = null

    /** Whether the last render drew a designed (block) paywall. */
    private var blockPaywall: Boolean = false

    // ——— per-render references (rebuilt by render()) ———
    private val selectionAppliers = mutableListOf<(String?) -> Unit>()
    private var ctaLabelView: TextView? = null
    private var ctaSpinner: ProgressBar? = null
    private var heroImageView: ImageView? = null

    // ——— hero image machinery (minimal fetch; no image library) ———
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var heroBitmap: Bitmap? = null
    private var heroBitmapUrl: String? = null
    private var heroFetchJob: Job? = null
    private var heroFetchUrl: String? = null

    /**
     * Renders a spinner in the CTA and blocks purchasing while true — the RN
     * `loading` prop. Only the CTA re-renders; scroll position and selection
     * are untouched.
     */
    public var loading: Boolean = false
        set(value) {
            field = value
            ctaLabelView?.visibility = if (value) INVISIBLE else VISIBLE
            ctaSpinner?.visibility = if (value) VISIBLE else GONE
        }

    /**
     * Controlled selection — the RN `selectedPackageId` prop. Non-null pins
     * the displayed selection to that package: tapping a row still calls
     * `onSelectPackage`, but the display only moves when the host sets this
     * property. Null (the default) lets the paywall manage selection itself.
     */
    public var selectedPackageId: String? = null
        set(value) {
            field = value
            // Same split as select(): a designed paywall redraws, because its
            // selected treatment is structural and no appliers are registered
            // on that path.
            if (blockPaywall) render() else applySelection()
        }

    init {
        clipChildren = false
        clipToPadding = false
    }

    /**
     * Bind a published config and render it. Each call is a fresh display —
     * internal selection resets and, when [client] is given, one
     * paywall.viewed is reported (REV-094, the analytics funnel's "Paywall
     * displayed" stage) — per bind, not per relayout, mirroring the RN
     * renderer's one-report-per-mount rule.
     *
     * Footer semantics (`config.footer`, legacy configs show all three):
     * explicit [onTerms]/[onPrivacy] handlers win over config URLs — the app
     * knows best how to open its own legal pages (in-app browser etc.); a
     * config URL is the no-handler fallback and opens via `ACTION_VIEW`.
     *
     * @param packages One row per purchasable package; `priceLabel` must be
     *   the store's localized price so the display never disagrees with the
     *   charge.
     * @param onPurchase Called with the selected packageId when the CTA is
     *   pressed.
     * @param selectedPackageId Seeds [selectedPackageId] (controlled mode);
     *   null for internal selection.
     * @param onClose Dismissal (REV-252). The HOST performs it — only the app
     *   knows whether that means finishing an activity, popping a fragment, or
     *   advancing onboarding — so the view never dismisses itself. Omit it and
     *   no close is drawn at all: a dead close button is worse than none.
     *   Passing [client] as well reports `paywall.closed` against this
     *   display's own view id.
     * @param disableViewTracking Opt out of the automatic view report while
     *   still passing [client].
     */
    public fun bind(
        config: PaywallConfig,
        packages: List<RevnixPaywallPackage>,
        onPurchase: (packageId: String) -> Unit,
        selectedPackageId: String? = null,
        onSelectPackage: ((packageId: String) -> Unit)? = null,
        onRestore: (() -> Unit)? = null,
        onTerms: (() -> Unit)? = null,
        onPrivacy: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null,
        theme: RevnixPaywallThemeOverride? = null,
        client: RevnixClient? = null,
        placementKey: String? = null,
        paywallId: String? = null,
        disableViewTracking: Boolean = false,
    ) {
        this.config = config
        this.packages = packages
        this.onPurchase = onPurchase
        this.onSelectPackage = onSelectPackage
        this.onRestore = onRestore
        this.onTerms = onTerms
        this.onPrivacy = onPrivacy
        this.onClose = onClose
        this.themeOverride = theme
        this.internalSelected = null
        this.selectedPackageId = selectedPackageId
        // A rebind is a new display, so the previous one's beacon must not
        // leak into it and mis-pair the next close (REV-252). Cancelling is
        // belt-and-braces — the field is replaced below either way.
        this.viewReport?.cancel()
        this.viewReport = null
        this.reportingClient = if (disableViewTracking) null else client
        this.diagnosticClient = client
        this.reportPlacementKey = placementKey
        this.reportPaywallId = paywallId
        render()
        if (client != null && !disableViewTracking) {
            // Fire-and-forget; the client swallows failures into diagnostics
            // and dispatches to IO internally.
            viewReport = viewScope.async { client.logPaywallDisplay(placementKey, paywallId) }
        }
    }

    /**
     * Runs the host's dismissal, reporting `paywall.closed` alongside it
     * (REV-252). The host's callback runs FIRST and unconditionally: the
     * beacon is best-effort, and an analytics failure must never be able to
     * trap the customer on the screen.
     */
    private fun closeAndReport() {
        onClose?.invoke()
        val client = reportingClient ?: return
        val report = viewReport ?: return
        val placementKey = reportPlacementKey
        val paywallId = reportPaywallId
        viewScope.launch {
            // Awaiting the view beacon is what keeps the pair intact when the
            // customer dismisses before it lands. It has usually finished long
            // ago, in which case this resumes immediately. A cancelled beacon
            // (the view was rebound or detached) reports nothing.
            val id = runCatching { report.await() }.getOrNull() ?: return@launch
            client.logPaywallClosed(id, placementKey, paywallId)
        }
    }

    // ——— lifecycle: the hero fetch is cancelled on detach and resumed on
    // re-attach; the decoded bitmap is kept so a rebind/relayout never
    // refetches. ———

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val url = config?.heroImageUrl
        if (!url.isNullOrEmpty() && (heroBitmap == null || heroBitmapUrl != url)) {
            ensureHeroFetch(url)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        heroFetchJob?.cancel()
        heroFetchJob = null
    }

    // ——— render ———

    /** Immutable per-render inputs every block reads. */
    private class Ctx(
        val config: PaywallConfig,
        val theme: RevnixPaywallTheme,
        val accent: Int,
        val cardBg: Int,
        val shown: List<RevnixPaywallPackage>,
    )

    private fun render() {
        selectionAppliers.clear()
        ctaLabelView = null
        ctaSpinner = null
        heroImageView = null
        removeAllViews()
        val config = this.config ?: return

        // Precedence: a designed paywall (`config.blocks`) wins over the
        // classic layouts below, which stay the fallback for every paywall
        // published before the block builder — so anything already live
        // renders unchanged.
        val blockDoc = PaywallBlockDoc.parse(config.blocks)
        blockPaywall = blockDoc != null && renderBlocks(blockDoc, config)
        if (blockPaywall) return

        // Base scheme comes from the dashboard config (mode: dark|light,
        // absent = dark for legacy configs); the host's explicit override
        // wins on top.
        val theme = resolvedTheme(config)
        val accent = colorOr(config.accent, DEFAULT_ACCENT)
        val cardBg = if (config.mode == "light") CARD_BG_LIGHT else CARD_BG_DARK

        // Same template semantics as the dashboard preview: "minimal" shows
        // only the highlighted package.
        val highlightId = config.highlightPackageId
        val shown = if (config.template == "minimal" && !highlightId.isNullOrEmpty()) {
            packages.filter { it.packageId == highlightId }
        } else {
            packages
        }
        shownPackages = shown
        setBackgroundColor(theme.background)

        val ctx = Ctx(config, theme, accent, cardBg, shown)

        // Content column: full width up to a readable 440dp, horizontally
        // centered on tablets/wide screens instead of stretching edge to edge.
        val content = MaxWidthColumn(context, dp(440))
        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.clipToPadding = false

        when (config.template) {
            "hero" -> {
                addHeroBanner(content, ctx)
                addFeatureChecks(content, ctx)
                addReviewCard(content, ctx)
                addPackageRows(content, ctx, shown, withBadge = true)
                addTail(content, ctx)
            }
            "timeline" -> {
                addHeroOrIcon(content, ctx)
                addHeadlineBlock(content, ctx)
                addTimeline(content, ctx)
                addReviewCard(content, ctx)
                addPackageRows(content, ctx, shown, withBadge = true)
                addTail(content, ctx)
            }
            "plans" -> {
                addHeroOrIcon(content, ctx)
                addHeadlineBlock(content, ctx)
                // Columns stay readable up to 3 — beyond that fall back to
                // stacked rows (never drop a purchasable package), same rule
                // as the dashboard preview.
                if (shown.size <= 3) {
                    addPlanColumns(content, ctx)
                } else {
                    addPackageRows(content, ctx, shown, withBadge = true)
                }
                addFeatureChecks(content, ctx)
                addReviewCard(content, ctx)
                addTail(content, ctx)
            }
            "feature-grid" -> {
                addHeroOrIcon(content, ctx)
                addHeadlineBlock(content, ctx)
                addFeatureGrid(content, ctx)
                addReviewCard(content, ctx)
                addPackageRows(content, ctx, shown, withBadge = true)
                addTail(content, ctx)
            }
            "offer" -> {
                addHeroOrIcon(content, ctx)
                addOfferPill(content, ctx)
                addHeadlineBlock(content, ctx)
                addOfferSpotlight(content, ctx)
                addReviewCard(content, ctx)
                addTail(content, ctx)
            }
            "reveal" -> {
                addProgressDots(content, ctx)
                addHeroOrIcon(content, ctx)
                addHeadlineBlock(content, ctx)
                addRevealCards(content, ctx)
                addPackageRows(content, ctx, shown, withBadge = true)
                addTail(content, ctx)
            }
            else -> {
                // focus | feature-list | minimal — the original structure,
                // unchanged for legacy configs (review/offer blocks only
                // exist when configured).
                addHeroOrIcon(content, ctx)
                addHeadlineBlock(content, ctx)
                if (config.template == "feature-list") addFeatureBullets(content, ctx)
                addReviewCard(content, ctx)
                addPackageRows(content, ctx, shown, withBadge = true)
                addTail(content, ctx)
            }
        }

        // fillViewport + CENTER gravity = content sits vertically centered on
        // tall screens (no dead bottom half) and scrolls normally when it
        // overflows — the RN `flexGrow + justifyContent: center` rule.
        val scrollContent = FrameLayout(context)
        scrollContent.setPadding(dp(24), dp(28), dp(24), dp(32))
        scrollContent.clipChildren = false
        scrollContent.clipToPadding = false
        scrollContent.addView(content, LayoutParams(MATCH, WRAP, Gravity.CENTER))

        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.addView(scrollContent, LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, MATCH))
        addClassicClose(theme)

        applySelection()
    }

    /**
     * The dismiss affordance the classic layouts get (REV-252).
     *
     * The nine `template` layouts have the same problem the designed ones had
     * — nothing on the screen closes them — and `onClose` is a parameter of
     * the shared bind(), so a host that wires it must get a close on either
     * path rather than silently nothing. Classic layouts author no elements of
     * their own, so there is never a design chip to suppress: the rule reduces
     * to "draw it whenever the host wired a handler".
     *
     * The designed path draws its own (inside the block renderer, where it can
     * see the tree), so this is never called there.
     */
    private fun addClassicClose(theme: RevnixPaywallTheme) {
        if (onClose == null) return
        val ink = theme.textPrimary
        val glyph = TextView(context).apply {
            text = "\u00d7"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ink)
            gravity = Gravity.CENTER
            contentDescription = "Close"
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(ink, 0x24)) // 14%, the same wash the block renderer uses
            }
            setOnClickListener { closeAndReport() }
        }
        addView(
            glyph,
            LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(14)
                marginEnd = dp(14)
            },
        )
    }

    // ——— shared blocks (each layout composes a subset; keep every block in
    // lockstep with the same-named block in RevnixPaywall.tsx) ———

    /** Classic header media: hero image card, or the accent icon tile. */
    private fun addHeroOrIcon(column: LinearLayout, ctx: Ctx) {
        val url = ctx.config.heroImageUrl
        if (!url.isNullOrEmpty()) {
            val image = ImageView(context)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            roundCorners(image, dpF(16f))
            val lp = LinearLayout.LayoutParams(MATCH, dp(180))
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(22)
            column.addView(image, lp)
            attachHero(image, url)
        } else {
            val tile = FrameLayout(context)
            tile.background = rounded(withAlpha(ctx.accent, 0x26), 16f)
            tile.addView(text("◆", 27f, ctx.accent), LayoutParams(WRAP, WRAP, Gravity.CENTER))
            val lp = LinearLayout.LayoutParams(dp(64), dp(64))
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(22)
            column.addView(tile, lp)
        }
    }

    /** Centered headline + optional subheadline. */
    private fun addHeadlineBlock(column: LinearLayout, ctx: Ctx) {
        column.addBlock(
            text(ctx.config.headline, 26f, ctx.theme.textPrimary, weight = 800, center = true),
            bottom = 10,
        )
        val sub = ctx.config.subheadline
        if (!sub.isNullOrEmpty()) {
            column.addBlock(
                text(sub, 15f, ctx.theme.textSecondary, lineHeightSp = 21f, center = true),
                bottom = 26,
            )
        }
    }

    /**
     * Hero layout banner: full-width media with a content-safe scrim overlay
     * carrying the headline/subheadline (always light-on-scrim). Falls back
     * to an accent field with the brand glyph when no hero image is set.
     */
    private fun addHeroBanner(column: LinearLayout, ctx: Ctx) {
        val banner = FrameLayout(context)
        banner.minimumHeight = dp(260)
        roundCorners(banner, dpF(20f))
        val url = ctx.config.heroImageUrl
        if (!url.isNullOrEmpty()) {
            val image = ImageView(context)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            banner.addView(image, LayoutParams(MATCH, MATCH))
            attachHero(image, url)
        } else {
            val fill = View(context)
            fill.setBackgroundColor(ctx.accent)
            banner.addView(fill, LayoutParams(MATCH, MATCH))
            val glyphWrap = FrameLayout(context)
            glyphWrap.setPadding(0, 0, 0, dp(72))
            glyphWrap.addView(
                // rgba(255,255,255,0.35)
                text("◆", 64f, 0x59FFFFFF),
                LayoutParams(WRAP, WRAP, Gravity.CENTER),
            )
            banner.addView(glyphWrap, LayoutParams(MATCH, MATCH))
        }
        val scrim = LinearLayout(context)
        scrim.orientation = LinearLayout.VERTICAL
        scrim.setBackgroundColor(0x73000000) // rgba(0,0,0,0.45)
        scrim.setPadding(dp(18), dp(16), dp(18), dp(16))
        scrim.addView(
            text(ctx.config.headline, 24f, 0xFFFFFFFF.toInt(), weight = 800),
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        val sub = ctx.config.subheadline
        if (!sub.isNullOrEmpty()) {
            // rgba(255,255,255,0.85)
            val subView = text(sub, 13.5f, 0xD9FFFFFF.toInt(), lineHeightSp = 19f)
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = dp(4)
            scrim.addView(subView, lp)
        }
        banner.addView(scrim, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        column.addBlock(banner, top = 8, bottom = 22)
    }

    /** Legacy feature bullets (feature-list layout). */
    private fun addFeatureBullets(column: LinearLayout, ctx: Ctx) {
        if (ctx.config.features.isEmpty()) return
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        ctx.config.features.forEachIndexed { i, feature ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.isBaselineAligned = false
            row.addView(
                text(feature.icon.orCheck(), 15f, ctx.accent, lineHeightSp = 21f),
                LinearLayout.LayoutParams(dp(24), WRAP),
            )
            val body = LinearLayout(context)
            body.orientation = LinearLayout.VERTICAL
            body.addView(
                text(feature.title, 15.5f, ctx.theme.textPrimary, weight = 600, lineHeightSp = 21f),
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            val description = feature.description
            if (!description.isNullOrEmpty()) {
                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                lp.topMargin = dp(1)
                body.addView(
                    text(description, 13f, ctx.theme.textSecondary, lineHeightSp = 18f),
                    lp,
                )
            }
            val bodyLp = LinearLayout.LayoutParams(0, WRAP, 1f)
            bodyLp.marginStart = dp(12)
            row.addView(body, bodyLp)
            val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
            if (i > 0) rowLp.topMargin = dp(14)
            list.addView(row, rowLp)
        }
        column.addBlock(list, bottom = 26)
    }

    /** Compact single-line checks (hero banner body, plans checklist). */
    private fun addFeatureChecks(column: LinearLayout, ctx: Ctx) {
        if (ctx.config.features.isEmpty()) return
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        ctx.config.features.forEachIndexed { i, feature ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.isBaselineAligned = false
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(
                text(feature.icon.orCheck(), 14f, ctx.accent),
                LinearLayout.LayoutParams(dp(20), WRAP),
            )
            val title = text(feature.title, 14.5f, ctx.theme.textPrimary, weight = 500, lineHeightSp = 20f)
            val titleLp = LinearLayout.LayoutParams(0, WRAP, 1f)
            titleLp.marginStart = dp(10)
            row.addView(title, titleLp)
            val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
            if (i > 0) rowLp.topMargin = dp(10)
            list.addView(row, rowLp)
        }
        column.addBlock(list, bottom = 24)
    }

    /**
     * Trial timeline: icon dots joined by an accent rail; the first step is
     * filled solid ("you are here"), later steps are tinted.
     */
    private fun addTimeline(column: LinearLayout, ctx: Ctx) {
        val features = ctx.config.features
        if (features.isEmpty()) return
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        features.forEachIndexed { i, feature ->
            val last = i == features.size - 1
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.isBaselineAligned = false

            val rail = LinearLayout(context)
            rail.orientation = LinearLayout.VERTICAL
            rail.gravity = Gravity.CENTER_HORIZONTAL
            val dot = FrameLayout(context)
            val dotBg = GradientDrawable()
            dotBg.shape = GradientDrawable.OVAL
            dotBg.setColor(if (i == 0) ctx.accent else withAlpha(ctx.accent, 0x26))
            dot.background = dotBg
            dot.addView(
                text(feature.icon.orCheck(), 14f, if (i == 0) ctx.theme.accentInk else ctx.accent),
                LayoutParams(WRAP, WRAP, Gravity.CENTER),
            )
            rail.addView(dot, LinearLayout.LayoutParams(dp(34), dp(34)))
            if (!last) {
                val line = View(context)
                line.setBackgroundColor(withAlpha(ctx.accent, 0x40))
                val lineLp = LinearLayout.LayoutParams(dp(2), 0, 1f)
                lineLp.topMargin = dp(4)
                lineLp.bottomMargin = dp(4)
                rail.addView(line, lineLp)
            }
            row.addView(rail, LinearLayout.LayoutParams(dp(34), MATCH))

            val body = LinearLayout(context)
            body.orientation = LinearLayout.VERTICAL
            body.setPadding(0, dp(6), 0, if (last) 0 else dp(22))
            body.addView(
                text(feature.title, 15.5f, ctx.theme.textPrimary, weight = 600, lineHeightSp = 21f),
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            val description = feature.description
            if (!description.isNullOrEmpty()) {
                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                lp.topMargin = dp(1)
                body.addView(
                    text(description, 13f, ctx.theme.textSecondary, lineHeightSp = 18f),
                    lp,
                )
            }
            val bodyLp = LinearLayout.LayoutParams(0, WRAP, 1f)
            bodyLp.marginStart = dp(12)
            row.addView(body, bodyLp)
            list.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        column.addBlock(list, bottom = 26)
    }

    /** Feature grid: two-column soft cards with an accent icon tile each. */
    private fun addFeatureGrid(column: LinearLayout, ctx: Ctx) {
        val features = ctx.config.features
        if (features.isEmpty()) return
        val grid = LinearLayout(context)
        grid.orientation = LinearLayout.VERTICAL
        var index = 0
        var rowIndex = 0
        while (index < features.size) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.isBaselineAligned = false
            // A lone trailing card grows to the full row, matching the RN
            // flexGrow behavior.
            val inRow = if (index + 1 < features.size) 2 else 1
            for (j in 0 until inRow) {
                val feature = features[index + j]
                val card = LinearLayout(context)
                card.orientation = LinearLayout.VERTICAL
                card.background = rounded(ctx.cardBg, 14f)
                card.setPadding(dp(13), dp(13), dp(13), dp(13))
                val tile = FrameLayout(context)
                tile.background = rounded(withAlpha(ctx.accent, 0x26), 10f)
                tile.addView(
                    text(feature.icon.orCheck(), 15f, ctx.accent),
                    LayoutParams(WRAP, WRAP, Gravity.CENTER),
                )
                val tileLp = LinearLayout.LayoutParams(dp(34), dp(34))
                tileLp.bottomMargin = dp(9)
                card.addView(tile, tileLp)
                card.addView(
                    text(feature.title, 13.5f, ctx.theme.textPrimary, weight = 600, lineHeightSp = 18f),
                    LinearLayout.LayoutParams(MATCH, WRAP),
                )
                val description = feature.description
                if (!description.isNullOrEmpty()) {
                    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                    lp.topMargin = dp(3)
                    card.addView(
                        text(description, 12f, ctx.theme.textSecondary, lineHeightSp = 16f),
                        lp,
                    )
                }
                val cardLp = LinearLayout.LayoutParams(0, MATCH, 1f)
                if (j > 0) cardLp.marginStart = dp(10)
                row.addView(card, cardLp)
            }
            val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
            if (rowIndex > 0) rowLp.topMargin = dp(10)
            grid.addView(row, rowLp)
            index += inRow
            rowIndex += 1
        }
        column.addBlock(grid, bottom = 24)
    }

    /** Reveal: onboarding-style progress dots (first filled with accent). */
    private fun addProgressDots(column: LinearLayout, ctx: Ctx) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        repeat(3) { i ->
            val dot = View(context)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(if (i == 0) ctx.accent else withAlpha(ctx.accent, 0x40))
            dot.background = bg
            val lp = LinearLayout.LayoutParams(dp(6), dp(6))
            if (i > 0) lp.marginStart = dp(6)
            row.addView(dot, lp)
        }
        column.addBlock(row, bottom = 18, width = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
    }

    /** Reveal: numbered benefit cards. */
    private fun addRevealCards(column: LinearLayout, ctx: Ctx) {
        val features = ctx.config.features
        if (features.isEmpty()) return
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        features.forEachIndexed { i, feature ->
            val card = LinearLayout(context)
            card.orientation = LinearLayout.HORIZONTAL
            card.isBaselineAligned = false
            card.background = rounded(ctx.cardBg, 14f)
            card.setPadding(dp(14), dp(14), dp(14), dp(14))
            val chip = FrameLayout(context)
            val chipBg = GradientDrawable()
            chipBg.shape = GradientDrawable.OVAL
            chipBg.setColor(withAlpha(ctx.accent, 0x26))
            chip.background = chipBg
            chip.addView(
                text("${i + 1}", 13f, ctx.accent, weight = 700),
                LayoutParams(WRAP, WRAP, Gravity.CENTER),
            )
            card.addView(chip, LinearLayout.LayoutParams(dp(26), dp(26)))
            val body = LinearLayout(context)
            body.orientation = LinearLayout.VERTICAL
            body.addView(
                text(feature.title, 15f, ctx.theme.textPrimary, weight = 600, lineHeightSp = 20f),
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            val description = feature.description
            if (!description.isNullOrEmpty()) {
                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                lp.topMargin = dp(1)
                body.addView(
                    text(description, 13f, ctx.theme.textSecondary, lineHeightSp = 18f),
                    lp,
                )
            }
            val bodyLp = LinearLayout.LayoutParams(0, WRAP, 1f)
            bodyLp.marginStart = dp(12)
            card.addView(body, bodyLp)
            val cardLp = LinearLayout.LayoutParams(MATCH, WRAP)
            if (i > 0) cardLp.topMargin = dp(10)
            list.addView(card, cardLp)
        }
        column.addBlock(list, bottom = 24)
    }

    /** Social proof card: star row (+ numeric rating), quote, attribution. */
    private fun addReviewCard(column: LinearLayout, ctx: Ctx) {
        val review = ctx.config.review ?: return
        val rating = review.rating
        val quote = review.quote
        if (rating == null && quote.isNullOrEmpty()) return
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = rounded(ctx.cardBg, 14f)
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        if (rating != null) {
            val stars = LinearLayout(context)
            stars.orientation = LinearLayout.HORIZONTAL
            stars.isBaselineAligned = false
            stars.gravity = Gravity.CENTER_VERTICAL
            val filled = Math.round(rating)
            for (n in 1..5) {
                val star = text("★", 15f, if (n <= filled) ctx.accent else ctx.theme.border)
                val lp = LinearLayout.LayoutParams(WRAP, WRAP)
                lp.leftMargin = dp(1)
                lp.rightMargin = dp(1)
                stars.addView(star, lp)
            }
            val ratingView = text(formatRating(rating), 13f, ctx.theme.textPrimary, weight = 600)
            val ratingLp = LinearLayout.LayoutParams(WRAP, WRAP)
            ratingLp.marginStart = dp(6)
            stars.addView(ratingView, ratingLp)
            card.addView(stars, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        if (!quote.isNullOrEmpty()) {
            val quoteView = text("“$quote”", 13.5f, ctx.theme.textPrimary, lineHeightSp = 19f, center = true)
            quoteView.setTypeface(quoteView.typeface, Typeface.ITALIC)
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = dp(8)
            card.addView(quoteView, lp)
        }
        val author = review.author
        if (!author.isNullOrEmpty()) {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.topMargin = dp(6)
            card.addView(text("— $author", 12f, ctx.theme.textSecondary), lp)
        }
        column.addBlock(card, bottom = 22)
    }

    /** Small count line under the CTA, e.g. "Join 2M+ users". */
    private fun addCountLine(column: LinearLayout, ctx: Ctx) {
        val count = ctx.config.review?.count
        if (count.isNullOrEmpty()) return
        column.addBlock(text(count, 12.5f, ctx.theme.textSecondary, center = true), bottom = 14)
    }

    /** Offer urgency line above the CTA. */
    private fun addUrgencyLine(column: LinearLayout, ctx: Ctx) {
        val urgency = ctx.config.offer?.urgencyText
        if (urgency.isNullOrEmpty()) return
        column.addBlock(text(urgency, 13f, ctx.accent, weight = 600, center = true), bottom = 10)
    }

    /**
     * The anchor is dashboard free text while priceLabel is the store's
     * localized price — never pair them when their currency symbols disagree,
     * or a EUR customer would see a struck-through USD anchor next to the
     * real charge (this file's "display never disagrees with the charge"
     * rule; kept in lockstep with the preview's anchorPriceFor). Symbol-less
     * anchors can't be judged and pass through.
     */
    private fun anchorPriceFor(config: PaywallConfig, priceLabel: String): String? {
        val anchor = config.offer?.strikethroughPrice
        if (anchor.isNullOrEmpty()) return null
        val symbol = CURRENCY_SYMBOL.find(anchor)?.value
        return if (symbol == null || priceLabel.contains(symbol)) anchor else null
    }

    /** Package price, with the struck-through anchor on the highlighted row. */
    private fun addPriceLine(parent: LinearLayout, ctx: Ctx, pkg: RevnixPaywallPackage, highlighted: Boolean) {
        val anchor = if (highlighted) anchorPriceFor(ctx.config, pkg.priceLabel) else null
        val price = text(pkg.priceLabel, 14f, ctx.theme.textSecondary)
        tabularNums(price)
        if (anchor != null) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            val anchorView = text(anchor, 13f, ctx.theme.textFaint)
            strikeThrough(anchorView)
            tabularNums(anchorView)
            row.addView(anchorView, LinearLayout.LayoutParams(WRAP, WRAP))
            val priceLp = LinearLayout.LayoutParams(WRAP, WRAP)
            priceLp.marginStart = dp(6)
            row.addView(price, priceLp)
            val rowLp = LinearLayout.LayoutParams(WRAP, WRAP)
            rowLp.topMargin = dp(2)
            parent.addView(row, rowLp)
        } else {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.topMargin = dp(2)
            parent.addView(price, lp)
        }
    }

    /**
     * Standard package rows (all layouts except plans columns / offer
     * spotlight). `withBadge = false` suppresses the row badge where the
     * layout already presents the badge elsewhere (offer pill). The badge
     * pill overlaps the top-right border and is capped at 80% of the row so
     * a long badge string ellipsizes instead of growing past the card edge.
     */
    private fun addPackageRows(
        column: LinearLayout,
        ctx: Ctx,
        list: List<RevnixPaywallPackage>,
        withBadge: Boolean,
    ) {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.clipChildren = false
        container.clipToPadding = false
        val badgeText = ctx.config.badgeText
        list.forEachIndexed { i, pkg ->
            val highlighted = pkg.packageId == ctx.config.highlightPackageId
            val badged = withBadge && highlighted && !badgeText.isNullOrEmpty()
            val row = CapFrame(context, 0.8f)
            row.clipChildren = false
            row.clipToPadding = false
            val bg = GradientDrawable()
            bg.cornerRadius = dpF(12f)
            bg.setColor(Color.TRANSPARENT)
            row.background = bg
            selectionAppliers += { selectedId ->
                val selected = pkg.packageId == selectedId
                if (selected) bg.setStroke(dp(2), ctx.accent) else bg.setStroke(dp(1), ctx.theme.border)
                row.isSelected = selected
            }
            row.setOnClickListener { select(pkg.packageId) }

            val inner = LinearLayout(context)
            inner.orientation = LinearLayout.VERTICAL
            inner.setPadding(dp(16), dp(15), dp(16), dp(15))
            inner.addView(
                text(pkg.title, 16f, ctx.theme.textPrimary, weight = 600),
                LinearLayout.LayoutParams(WRAP, WRAP),
            )
            addPriceLine(inner, ctx, pkg, highlighted)
            row.addView(inner, LayoutParams(MATCH, WRAP))

            if (badged) {
                checkNotNull(badgeText) // implied by `badged`
                val badge = text(badgeText, 11f, ctx.theme.accentInk, weight = 700)
                badge.maxLines = 1
                badge.ellipsize = TextUtils.TruncateAt.END
                badge.background = rounded(ctx.accent, 20f)
                badge.setPadding(dp(10), dp(3), dp(10), dp(3))
                row.capTarget = badge
                val lp = LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
                lp.marginEnd = dp(14)
                lp.topMargin = -dp(11)
                row.addView(badge, lp)
            }

            val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
            if (i > 0) rowLp.topMargin = dp(12)
            container.addView(row, rowLp)
        }
        column.addBlock(container, bottom = 22)
    }

    /**
     * Plans layout: packages side by side as equal-weight tier columns; the
     * highlighted tier carries the badge pill inside the column.
     */
    private fun addPlanColumns(column: LinearLayout, ctx: Ctx) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.isBaselineAligned = false
        val badgeText = ctx.config.badgeText
        ctx.shown.forEachIndexed { i, pkg ->
            val highlighted = pkg.packageId == ctx.config.highlightPackageId
            val col = LinearLayout(context)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(dp(8), dp(14), dp(8), dp(14))
            val bg = GradientDrawable()
            bg.cornerRadius = dpF(14f)
            bg.setColor(Color.TRANSPARENT)
            col.background = bg
            selectionAppliers += { selectedId ->
                val selected = pkg.packageId == selectedId
                if (selected) bg.setStroke(dp(2), ctx.accent) else bg.setStroke(dp(1), ctx.theme.border)
                col.isSelected = selected
            }
            col.setOnClickListener { select(pkg.packageId) }

            if (highlighted && !badgeText.isNullOrEmpty()) {
                val badge = text(badgeText, 11f, ctx.theme.accentInk, weight = 700)
                badge.maxLines = 1
                badge.ellipsize = TextUtils.TruncateAt.END
                badge.background = rounded(ctx.accent, 20f)
                badge.setPadding(dp(8), dp(2), dp(8), dp(2))
                val lp = LinearLayout.LayoutParams(WRAP, WRAP)
                lp.bottomMargin = dp(7)
                col.addView(badge, lp)
            }
            col.addView(
                text(pkg.title, 14f, ctx.theme.textPrimary, weight = 600, lineHeightSp = 19f, center = true),
                LinearLayout.LayoutParams(WRAP, WRAP),
            )
            val anchor = if (highlighted) anchorPriceFor(ctx.config, pkg.priceLabel) else null
            if (anchor != null) {
                val anchorView = text(anchor, 13f, ctx.theme.textFaint)
                strikeThrough(anchorView)
                tabularNums(anchorView)
                col.addView(anchorView, LinearLayout.LayoutParams(WRAP, WRAP))
            }
            val price = text(pkg.priceLabel, 15f, ctx.theme.textPrimary, weight = 700)
            tabularNums(price)
            val priceLp = LinearLayout.LayoutParams(WRAP, WRAP)
            priceLp.topMargin = dp(4)
            col.addView(price, priceLp)

            val colLp = LinearLayout.LayoutParams(0, MATCH, 1f)
            if (i > 0) colLp.marginStart = dp(8)
            row.addView(col, colLp)
        }
        column.addBlock(row, bottom = 22)
    }

    /** Offer layout: the badge becomes a large centered pill. */
    private fun addOfferPill(column: LinearLayout, ctx: Ctx) {
        val badgeText = ctx.config.badgeText
        if (badgeText.isNullOrEmpty()) return
        val wrap = CapFrame(context, 0.8f)
        val pill = text(badgeText, 12f, ctx.theme.accentInk, weight = 700)
        pill.maxLines = 1
        pill.ellipsize = TextUtils.TruncateAt.END
        pill.background = rounded(ctx.accent, 20f)
        pill.setPadding(dp(12), dp(5), dp(12), dp(5))
        wrap.capTarget = pill
        wrap.addView(pill, LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
        column.addBlock(wrap, bottom = 14)
    }

    /**
     * Offer layout: the highlighted (else first) package renders as a
     * spotlight card with the anchor price; remaining packages follow as
     * standard rows without the row badge (the offer pill carries it).
     */
    private fun addOfferSpotlight(column: LinearLayout, ctx: Ctx) {
        val spotlight = ctx.shown.firstOrNull { it.packageId == ctx.config.highlightPackageId }
            ?: ctx.shown.firstOrNull()
            ?: return
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(18), dp(18), dp(18), dp(18))
        val bg = GradientDrawable()
        bg.cornerRadius = dpF(16f)
        bg.setColor(Color.TRANSPARENT)
        card.background = bg
        selectionAppliers += { selectedId ->
            val selected = spotlight.packageId == selectedId
            // The spotlight border is always 2dp; only its color reacts.
            bg.setStroke(dp(2), if (selected) ctx.accent else ctx.theme.border)
            card.isSelected = selected
        }
        card.setOnClickListener { select(spotlight.packageId) }

        card.addView(
            text(spotlight.title, 16f, ctx.theme.textPrimary, weight = 600),
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
        val priceRow = LinearLayout(context)
        priceRow.orientation = LinearLayout.HORIZONTAL
        val anchor = anchorPriceFor(ctx.config, spotlight.priceLabel)
        if (anchor != null) {
            val anchorView = text(anchor, 15f, ctx.theme.textFaint)
            strikeThrough(anchorView)
            tabularNums(anchorView)
            priceRow.addView(anchorView, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val price = text(spotlight.priceLabel, 24f, ctx.theme.textPrimary, weight = 800)
        tabularNums(price)
        val priceLp = LinearLayout.LayoutParams(WRAP, WRAP)
        if (anchor != null) priceLp.marginStart = dp(8)
        priceRow.addView(price, priceLp)
        val priceRowLp = LinearLayout.LayoutParams(WRAP, WRAP)
        priceRowLp.topMargin = dp(6)
        card.addView(priceRow, priceRowLp)
        column.addBlock(card, bottom = 12)

        if (ctx.shown.size > 1) {
            addPackageRows(
                column,
                ctx,
                ctx.shown.filter { it.packageId != spotlight.packageId },
                withBadge = false,
            )
        }
    }

    /** CTA button; [loading] swaps the label for an indeterminate spinner. */
    private fun addCta(column: LinearLayout, ctx: Ctx) {
        val cta = FrameLayout(context)
        cta.background = rounded(ctx.accent, 12f)
        cta.setPadding(dp(16), dp(16), dp(16), dp(16))
        val label = text(ctx.config.ctaLabel, 16.5f, ctx.theme.accentInk, weight = 700)
        cta.addView(label, LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val spinner = ProgressBar(context)
        spinner.indeterminateTintList = ColorStateList.valueOf(ctx.theme.accentInk)
        cta.addView(spinner, LayoutParams(dp(20), dp(20), Gravity.CENTER))
        // The label goes INVISIBLE (not GONE) so the CTA keeps its height
        // while the spinner shows.
        label.visibility = if (loading) INVISIBLE else VISIBLE
        spinner.visibility = if (loading) VISIBLE else GONE
        ctaLabelView = label
        ctaSpinner = spinner
        cta.setOnClickListener {
            val selectedId = currentSelectedId()
            if (!loading && selectedId != null) onPurchase?.invoke(selectedId)
        }
        column.addBlock(cta, bottom = 14)
    }

    private class FooterItem(val label: String, val action: (() -> Unit)?)

    /**
     * Footer links (Restore · Terms · Privacy). Dashboard-configured
     * (`config.footer`); a legacy config without the field keeps the original
     * always-on footer. Explicit host handlers win over config URLs; the URL
     * is the no-handler fallback and opens via `ACTION_VIEW`.
     */
    private fun addFooter(column: LinearLayout, ctx: Ctx) {
        val footer = ctx.config.footer
        val items = mutableListOf<FooterItem>()
        if (footer?.showRestore != false) items += FooterItem("Restore", onRestore)
        if (footer?.showTerms != false) items += FooterItem("Terms", onTerms ?: openUrlAction(footer?.termsUrl))
        if (footer?.showPrivacy != false) items += FooterItem("Privacy", onPrivacy ?: openUrlAction(footer?.privacyUrl))
        if (items.isEmpty()) return
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.isBaselineAligned = false
        row.gravity = Gravity.CENTER_VERTICAL
        items.forEachIndexed { i, item ->
            if (i > 0) {
                val dot = text(" · ", 13f, ctx.theme.textFaint)
                dot.setPadding(0, dp(4), 0, dp(4))
                row.addView(dot, LinearLayout.LayoutParams(WRAP, WRAP))
            }
            val link = text(item.label, 13f, ctx.theme.textFaint)
            link.setPadding(dp(4), dp(4), dp(4), dp(4))
            val action = item.action
            if (action != null) link.setOnClickListener { action() }
            row.addView(link, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        column.addBlock(row, width = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
    }

    /** Tail shared by every layout: urgency → CTA → count → footer. */
    private fun addTail(column: LinearLayout, ctx: Ctx) {
        addUrgencyLine(column, ctx)
        addCta(column, ctx)
        addCountLine(column, ctx)
        addFooter(column, ctx)
    }

    // ——— selection ———

    private fun currentSelectedId(): String? {
        val internal = internalSelected
        return selectedPackageId
            ?: internal?.takeIf { id -> shownPackages.any { it.packageId == id } }
            ?: shownPackages.firstOrNull { it.packageId == config?.highlightPackageId }?.packageId
            ?: shownPackages.firstOrNull()?.packageId
    }

    private fun select(packageId: String) {
        internalSelected = packageId
        onSelectPackage?.invoke(packageId)
        // A designed paywall's selected treatment is structural, not just a
        // border: a badge and a sub-line appear, and an arbitrary
        // `selectedStyle` merges in. So it is redrawn, while the classic
        // layouts restyle in place through the appliers.
        if (blockPaywall) render() else applySelection()
    }

    private fun applySelection() {
        val selectedId = currentSelectedId()
        for (applier in selectionAppliers) applier(selectedId)
    }

    // ——— hero image fetch: plain HttpURLConnection + BitmapFactory on a
    // background dispatcher, cancelled on detach, decoded bitmap cached
    // per URL so relayouts and rebinds never refetch. ———

    private fun attachHero(target: ImageView, url: String) {
        heroImageView = target
        val cached = heroBitmap
        if (cached != null && heroBitmapUrl == url) {
            target.setImageBitmap(cached)
            return
        }
        ensureHeroFetch(url)
    }

    private fun ensureHeroFetch(url: String) {
        if (heroFetchUrl == url && heroFetchJob?.isActive == true) return
        heroFetchJob?.cancel()
        heroFetchUrl = url
        heroFetchJob = viewScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                try {
                    if (connection.responseCode in 200..299) {
                        connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    } else {
                        null
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bitmap != null && heroFetchUrl == url) {
                    heroBitmap = bitmap
                    heroBitmapUrl = url
                    // Like the RN Image, a failed load simply shows nothing.
                    if (config?.heroImageUrl == url) heroImageView?.setImageBitmap(bitmap)
                }
            }
        }
    }

    // ——— helpers ———

    private fun resolvedTheme(config: PaywallConfig): RevnixPaywallTheme {
        val base = if (config.mode == "light") RevnixPaywallTheme.LIGHT else RevnixPaywallTheme.DEFAULT
        val override = themeOverride ?: return base
        return RevnixPaywallTheme(
            background = override.background ?: base.background,
            textPrimary = override.textPrimary ?: base.textPrimary,
            textSecondary = override.textSecondary ?: base.textSecondary,
            textFaint = override.textFaint ?: base.textFaint,
            border = override.border ?: base.border,
            accentInk = override.accentInk ?: base.accentInk,
        )
    }

    private fun openUrlAction(url: String?): (() -> Unit)? {
        if (url.isNullOrEmpty()) return null
        return {
            // No browser installed is not the paywall's problem to surface.
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    private fun text(
        value: CharSequence,
        sizeSp: Float,
        color: Int,
        weight: Int = 400,
        lineHeightSp: Float? = null,
        center: Boolean = false,
    ): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setTextColor(color)
        setWeight(view, weight)
        if (lineHeightSp != null) setLineHeightSp(view, lineHeightSp)
        if (center) view.gravity = Gravity.CENTER_HORIZONTAL
        return view
    }

    private fun setWeight(view: TextView, weight: Int) {
        view.typeface = if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(Typeface.DEFAULT, weight, false)
        } else when {
            weight >= 700 -> Typeface.DEFAULT_BOLD
            weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }

    /** RN lineHeight equivalent (applied after size/typeface are set). */
    private fun setLineHeightSp(view: TextView, sp: Float) {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        if (Build.VERSION.SDK_INT >= 28) {
            view.setLineHeight(px.roundToInt())
        } else {
            val metrics = view.paint.fontMetricsInt
            view.setLineSpacing(px - (metrics.descent - metrics.ascent), 1f)
        }
    }

    private fun tabularNums(view: TextView) {
        view.fontFeatureSettings = "'tnum' on"
    }

    private fun strikeThrough(view: TextView) {
        view.paintFlags = view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.cornerRadius = dpF(radiusDp)
        drawable.setColor(color)
        return drawable
    }

    private fun roundCorners(view: View, radius: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        view.clipToOutline = true
    }

    /** RN `${accent}26` / `${accent}40` hex-suffix tints. */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun colorOr(hex: String?, fallback: Int): Int =
        hex?.let { value -> runCatching { Color.parseColor(value) }.getOrNull() } ?: fallback

    /** Mirrors JS `String(rating)` — integral doubles print without ".0". */
    private fun formatRating(rating: Double): String =
        if (rating % 1.0 == 0.0) rating.toLong().toString() else rating.toString()

    private fun String?.orCheck(): String = if (isNullOrEmpty()) "✓" else this

    private fun LinearLayout.addBlock(
        view: View,
        top: Int = 0,
        bottom: Int = 0,
        width: Int = MATCH,
        gravity: Int = Gravity.NO_GRAVITY,
    ) {
        val lp = LinearLayout.LayoutParams(width, WRAP)
        lp.topMargin = dp(top)
        lp.bottomMargin = dp(bottom)
        if (gravity != Gravity.NO_GRAVITY) lp.gravity = gravity
        addView(view, lp)
    }

    /**
     * Draws a designed paywall, reporting whether it succeeded.
     *
     * Wrapped so a malformed document costs the paywall its DESIGN, not the
     * purchase: if the tree fails to draw, `render()` carries on into the
     * classic layout, which is a working screen the customer can still buy
     * from. A shipped app cannot be patched from our side, so the fallback
     * matters more than the failure being loud.
     */
    private fun renderBlocks(blockDoc: PaywallBlockDoc, config: PaywallConfig): Boolean {
        val shown = packages
        shownPackages = shown
        val selected = selectedPackageIdOrDefault(config, shown)
        val blockPackages = shown.map {
            BlockPackage(
                packageId = it.packageId,
                title = it.title,
                priceLabel = it.priceLabel,
                period = it.period,
                amountMinor = it.amountMinor,
                currency = it.currency,
            )
        }
        val screen = runCatching {
            PaywallBlockRenderer(
                context,
                BlockContext(
                    doc = blockDoc,
                    packages = blockPackages,
                    selectedPackageId = selected,
                    heroImageUrl = config.heroImageUrl,
                    footerTermsUrl = config.footer?.termsUrl,
                    footerPrivacyUrl = config.footer?.privacyUrl,
                    onPurchase = { id -> if (!loading) onPurchase?.invoke(id) },
                    onSelect = { id -> select(id) },
                    onRestore = onRestore,
                    onTerms = onTerms,
                    onPrivacy = onPrivacy,
                    onClose = onClose?.let { { closeAndReport() } },
                    onDiagnostic = diagnosticClient?.let { c -> { m: String -> c.reportRenderDiagnostic(m) } },
                ),
            ).renderScreen()
        }.getOrNull() ?: return false

        // The flat colour behind the rendered screen. A gradient ground has no
        // single colour, so it collapses to its base here rather than leaving
        // the view's own backdrop showing through.
        revnixBlockColor(revnixBackgroundBaseColor(blockDoc.background), blockDoc)
            ?.let { setBackgroundColor(it) }
        addView(
            screen,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        // The paywall.viewed report is raised by bind(), which both render
        // paths go through — a designed paywall reports exactly like a
        // classic one, and neither path can double-count.
        return true
    }

    /**
     * The package the design emphasizes: the host's controlled selection, then
     * the internal one, then the config's highlight, then the first package.
     */
    private fun selectedPackageIdOrDefault(
        config: PaywallConfig,
        shown: List<RevnixPaywallPackage>,
    ): String? {
        val controlled = selectedPackageId
        if (controlled != null && shown.any { it.packageId == controlled }) return controlled
        val internal = internalSelected
        if (internal != null && shown.any { it.packageId == internal }) return internal
        val highlight = config.highlightPackageId
        if (highlight != null && shown.any { it.packageId == highlight }) return highlight
        return shown.firstOrNull()?.packageId
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dpF(value: Float): Float = value * resources.displayMetrics.density
}

/**
 * The RN `maxWidth: 440` content rule: full width up to the cap, then
 * horizontally centered by the parent's gravity.
 */
private class MaxWidthColumn(context: Context, private val maxWidthPx: Int) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (mode != MeasureSpec.UNSPECIFIED && size > maxWidthPx) {
            MeasureSpec.makeMeasureSpec(maxWidthPx, mode)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}

/**
 * The RN `maxWidth: "80%"` rule for the badge / offer pill: absolute (or
 * centered) text takes no width constraint from its card, so a long string
 * used to grow past the card edge — cap it at a fraction of this frame and
 * let the single-line ellipsize do the rest.
 */
private class CapFrame(context: Context, private val fraction: Float) : FrameLayout(context) {
    var capTarget: TextView? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = capTarget
        if (target != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val cap = (MeasureSpec.getSize(widthMeasureSpec) * fraction).toInt()
            if (cap > 0 && target.maxWidth != cap) target.maxWidth = cap
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
