// Selected context — the pure half of the render contract (REV-262).
//
// A designed paywall's plan cards react to the selected package: a pinned or
// repeated card and EVERYTHING inside it take their `selectedStyle` and honour
// their `visibility` while that card's package is the selected one. The rules
// are simple but they are the same on seven renderers, and the Android View
// renderer cannot run on the JVM test task — so they live here, framework-
// free, and `PaywallSelectionWireTest` walks the shared fixture through them.
//
// Contract: cto-review/2026-09-03-paywall-render-contract-v2.md in revnix-app.

package com.revnix

/**
 * Where a block sits relative to the selected package.
 *
 * [pkg] is the package of the nearest pinned (`packageIndex`) or repeated
 * (`repeat: "packages"`) card above the block, or null outside any. [selected]
 * is whether that package is the selected one. A block with no [pkg] is never
 * in selected context: `selectedStyle` is ignored there and `visibility` too.
 */
public data class BlockSelectionContext(
    public val pkg: BlockPackage? = null,
    public val selected: Boolean = false,
) {
    /** True inside a pinned or repeated card. */
    public val inPackageContext: Boolean get() = pkg != null

    /**
     * The package this block's copy tags resolve against: the card's own
     * package inside one, the SELECTED package outside — so the renewal line
     * every template carries ("7 days free, then {price}/{period_short}")
     * follows the customer's choice instead of drawing a literal `{price}`.
     */
    public fun tagPackage(selectedPackage: BlockPackage?): BlockPackage? = pkg ?: selectedPackage

    public companion object {
        /** The root of the tree: outside every package card. */
        public val NONE: BlockSelectionContext = BlockSelectionContext()
    }
}

/**
 * The selected package: the first of [candidates] the offering actually
 * carries, else the first package. Callers pass them in precedence order —
 * the host's controlled selection, the renderer's own after a tap, then
 * `config.highlightPackageId`. Same rule the dashboard preview uses with the
 * highlight alone (it has no taps).
 */
public fun revnixSelectedPackageId(
    packages: List<BlockPackage>,
    vararg candidates: String?,
): String? {
    for (candidate in candidates) {
        if (candidate != null && packages.any { it.packageId == candidate }) return candidate
    }
    return packages.firstOrNull()?.packageId
}

/** The context one package establishes — for a pinned card, or one repeat instance. */
public fun revnixPackageContext(
    pkg: BlockPackage?,
    selectedPackageId: String?,
): BlockSelectionContext =
    BlockSelectionContext(pkg, pkg != null && pkg.packageId == selectedPackageId)

/**
 * The context a (non-repeating) card establishes for itself and its children.
 *
 * A pinned card starts a new context from its package; any other card
 * inherits its parent's (a plain card inside a plan card is still "inside the
 * plan card"). Null means the card is DROPPED: it names a package the offering
 * does not reach, and drawing it would show unresolved tags. Repeat cards are
 * the caller's loop — one [revnixPackageContext] per instance.
 */
public fun revnixCardContext(
    card: PaywallBlock.Card,
    parent: BlockSelectionContext,
    packages: List<BlockPackage>,
    selectedPackageId: String?,
): BlockSelectionContext? {
    val index = card.packageIndex ?: return parent
    val pinned = packages.getOrNull(index) ?: return null
    return revnixPackageContext(pinned, selectedPackageId)
}

/**
 * The style a block draws with: `selectedStyle` merged over `style` in
 * selected context, plain `style` otherwise. Null stays null, so a block
 * with no style at all costs nothing.
 */
public fun revnixEffectiveStyle(block: PaywallBlock, context: BlockSelectionContext): BlockStyle? {
    val selected = block.selectedStyle ?: return block.style
    if (!context.selected) return block.style
    return (block.style ?: BlockStyle()).merging(selected)
}

/**
 * Whether a block is drawn at all in [context]. A block without `visibility`
 * is always drawn, and so is one outside any package card — whatever its
 * `visibility` says, because a root-level block that vanishes is a design
 * with a hole in it, never intent.
 */
public fun revnixIsBlockVisible(block: PaywallBlock, context: BlockSelectionContext): Boolean {
    val visibility = block.visibility ?: return true
    if (!context.inPackageContext) return true
    return when (visibility) {
        BlockVisibility.Selected -> context.selected
        BlockVisibility.Unselected -> !context.selected
    }
}

/**
 * One block as the renderer would draw it: its context, the style it takes,
 * whether it is on screen, and its copy with the tags filled. What the
 * contract test asserts, and what a renderer that cannot be unit-tested
 * should agree with.
 */
public data class ResolvedBlock(
    public val block: PaywallBlock,
    public val context: BlockSelectionContext,
    public val style: BlockStyle?,
    public val visible: Boolean,
    /** Resolved copy for a text block or a button's label; null otherwise. */
    public val text: String?,
)

/**
 * Walks the tree in draw order and resolves every block against
 * [selectedPackageId]. A repeat card yields one entry per package; a pinned
 * card the offering does not reach yields none (it is dropped, like the
 * renderer drops it). Descendants of a hidden block are listed hidden.
 */
public fun revnixResolveBlocks(
    doc: PaywallBlockDoc,
    packages: List<BlockPackage>,
    selectedPackageId: String?,
): List<ResolvedBlock> {
    val selectedPackage = packages.firstOrNull { it.packageId == selectedPackageId }
    val out = mutableListOf<ResolvedBlock>()

    fun emit(block: PaywallBlock, context: BlockSelectionContext, shown: Boolean): Boolean {
        val visible = shown && revnixIsBlockVisible(block, context)
        val pkg = context.tagPackage(selectedPackage)
        val text = when (block) {
            is PaywallBlock.Text -> revnixResolveTags(block.text, pkg, packages)
            is PaywallBlock.Button -> revnixResolveTags(block.label, pkg, packages)
            else -> null
        }
        out.add(ResolvedBlock(block, context, revnixEffectiveStyle(block, context), visible, text))
        return visible
    }

    fun walk(block: PaywallBlock, parent: BlockSelectionContext, shown: Boolean) {
        if (block !is PaywallBlock.Card) {
            emit(block, parent, shown)
            return
        }
        if (block.repeat == "packages") {
            val instances: List<BlockPackage?> = packages.ifEmpty { listOf(null) }
            for (each in instances) {
                val context = revnixPackageContext(each, selectedPackageId)
                val visible = emit(block, context, shown)
                for (child in block.children) walk(child, context, visible)
            }
            return
        }
        val context = revnixCardContext(block, parent, packages, selectedPackageId) ?: return
        val visible = emit(block, context, shown)
        for (child in block.children) walk(child, context, visible)
    }

    for (block in doc.blocks) walk(block, BlockSelectionContext.NONE, true)
    return out
}
