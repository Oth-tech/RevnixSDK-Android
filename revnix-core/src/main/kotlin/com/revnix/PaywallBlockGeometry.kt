package com.revnix

/**
 * Geometry paint strings — the block style fields whose value is a small piece
 * of CSS geometry rather than a number.
 *
 * `clipPath`, `translate` and `fillSize` were named by revnix-app's BlockStyle
 * and by NONE of the native renderers, so [BlockStyle.from] dropped them and
 * the renderer never saw them. 13 of the 25 shipped template categories set at
 * least one, which meant a starburst badge drew as a rectangle, a pinned
 * "MOST POPULAR" chip sat half a width off centre, and a tiled grid wash
 * painted as one stretched stripe — silently, and only on device, never in the
 * dashboard preview the design was approved in.
 *
 * Everything here is a PURE parser over a string, living in `revnix-core` so it
 * is tested on the JVM with no Android or Compose host. Parsing is total in the
 * same way the rest of the block model is: an unreadable value yields null and
 * the block draws unclipped and unmoved — never wrong, never crashed.
 *
 * Kept in lockstep with the Swift `PaywallBlockGeometry.swift`; the two are two
 * interpreters of the same document and must agree on every form below.
 */

/**
 * A CSS `<length-percentage>`, kept as both halves so one type covers every
 * form the designs use: `16px` is [px] alone, `50%` is [fraction] alone, and
 * `calc(100% - 16px)` — which the ticket-notch clips need — is the two
 * together. Resolving is then a single multiply-add against the box.
 */
public data class RevnixLength(
    /** Fraction of the reference box on this axis: `50%` → 0.5. */
    public val fraction: Double = 0.0,
    /** Fixed px added after the fraction: `calc(100% - 16px)` → -16. */
    public val px: Double = 0.0,
) {
    /** This length against a box edge of [extent] px. */
    public fun resolvedAgainst(extent: Double): Double = fraction * extent + px

    /**
     * True when the value needs no measuring — a pure-px length can be applied
     * without laying the block out first, which keeps the common case cheap.
     */
    public val isAbsolute: Boolean get() = fraction == 0.0
}

/**
 * One `<length-percentage>` token: `50%`, `-8px`, a bare `0`, or the
 * `calc(<pct> ± <px>)` form. Returns null for anything else — a `var()`, an
 * unsupported unit — so the caller declines the whole value rather than
 * clipping against a number it guessed.
 */
public fun revnixParseLength(token: String?): RevnixLength? {
    val raw = token?.trim() ?: return null
    if (raw.isEmpty()) return null

    // calc(100% - 16px) / calc(50% + 4px). Only the single-operator form the
    // designs actually write; nested arithmetic is declined, not approximated.
    if (raw.lowercase().startsWith("calc(") && raw.endsWith(")")) {
        val inner = raw.substring(5, raw.length - 1)
        for (i in 1 until inner.length) {
            val c = inner[i]
            if (c != '+' && c != '-') continue
            // An operator in CSS calc must be surrounded by whitespace, which
            // is also what stops `1e-3` from splitting here.
            if (inner[i - 1] != ' ') continue
            val a = revnixParseLength(inner.substring(0, i)) ?: return null
            val b = revnixParseLength(inner.substring(i + 1)) ?: return null
            val sign = if (c == '-') -1.0 else 1.0
            return RevnixLength(a.fraction + sign * b.fraction, a.px + sign * b.px)
        }
        return revnixParseLength(inner)
    }

    if (raw.endsWith("%")) {
        val n = raw.dropLast(1).toDoubleOrNull() ?: return null
        return RevnixLength(fraction = n / 100.0)
    }
    if (raw.lowercase().endsWith("px")) {
        val n = raw.dropLast(2).toDoubleOrNull() ?: return null
        return RevnixLength(px = n)
    }
    // A bare number is px, which is how the designs write `0`.
    val n = raw.toDoubleOrNull() ?: return null
    return RevnixLength(px = n)
}

/**
 * Splits on [separator] at PAREN DEPTH ZERO, so the spaces and commas inside
 * `calc(100% - 16px)` stay part of their token. Splitting naively is exactly
 * how a calc-bearing polygon turns into garbage points.
 */
internal fun revnixSplitTopLevel(value: String, separator: Char): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (ch in value) {
        when {
            ch == '(' -> { depth++; current.append(ch) }
            ch == ')' -> { if (depth > 0) depth--; current.append(ch) }
            ch == separator && depth == 0 -> { out.add(current.toString()); current.clear() }
            else -> current.append(ch)
        }
    }
    out.add(current.toString())
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * A parsed CSS `translate`: an x and a y, each of which may be a percentage OF
 * THE BLOCK'S OWN SIZE. That "own size" is the whole point — the designs centre
 * a pinned badge with `left: 50%` plus `translate: "-50% 0"`, and resolving the
 * -50% against anything but the badge's own width puts it somewhere else.
 */
public data class RevnixTranslate(public val x: RevnixLength, public val y: RevnixLength) {
    /** True when neither axis needs the block measured. */
    public val isAbsolute: Boolean get() = x.isAbsolute && y.isAbsolute
}

/**
 * `"-50% 0"` / `"0 -8px"` / `"12px"`. A single component sets x and leaves y at
 * zero, as CSS does.
 */
public fun revnixParseTranslate(css: String?): RevnixTranslate? {
    if (css.isNullOrBlank()) return null
    val parts = revnixSplitTopLevel(css, ' ')
    if (parts.isEmpty() || parts.size > 3) return null
    val x = revnixParseLength(parts[0]) ?: return null
    // A third component is the z axis, meaningless in this 2D renderer; the
    // x/y prefix is still honoured rather than dropped.
    val y = if (parts.size > 1) revnixParseLength(parts[1]) ?: return null else RevnixLength()
    return RevnixTranslate(x, y)
}

/** One polygon vertex, each axis resolved against its own edge of the box. */
public data class RevnixPolygonPoint(public val x: RevnixLength, public val y: RevnixLength)

/**
 * `polygon(50% 0,100% 100%,0 100%)` → its vertices.
 *
 * Only the `polygon()` form is read. It is the only one the 250 shipped presets
 * use (starbursts, ticket notches, chevron rails), and the one form that maps
 * exactly onto a path — `inset()`, `circle()` and `path()` would each need
 * their own geometry and none appear in the library. An optional leading fill
 * rule (`nonzero` / `evenodd`) is accepted and ignored: both rules draw these
 * outlines the same way.
 */
public fun revnixParsePolygon(css: String?): List<RevnixPolygonPoint>? {
    val trimmed = css?.trim() ?: return null
    if (!trimmed.lowercase().startsWith("polygon(") || !trimmed.endsWith(")")) return null
    val inner = trimmed.substring("polygon(".length, trimmed.length - 1)

    val groups = revnixSplitTopLevel(inner, ',').toMutableList()
    groups.firstOrNull()?.lowercase()?.let { rule ->
        if (rule == "nonzero" || rule == "evenodd") groups.removeAt(0)
    }
    // Two points describe a line, which clips a block away to nothing. Better
    // to decline and leave the block visible than to erase it.
    if (groups.size < 3) return null

    val points = ArrayList<RevnixPolygonPoint>(groups.size)
    for (group in groups) {
        val axes = revnixSplitTopLevel(group, ' ')
        if (axes.size != 2) return null
        val x = revnixParseLength(axes[0]) ?: return null
        val y = revnixParseLength(axes[1]) ?: return null
        points.add(RevnixPolygonPoint(x, y))
    }
    return points
}

/**
 * A parsed `fillSize`. `Cover`/`Contain` are carried for completeness; the case
 * that changes what ships is [Tile], because a repeating-gradient wash with no
 * tile size stretches into a single band.
 */
public sealed class RevnixFillSize {
    public object Cover : RevnixFillSize()
    public object Contain : RevnixFillSize()
    public data class Tile(public val width: RevnixLength, public val height: RevnixLength) :
        RevnixFillSize()
}

/**
 * `"18px 18px"` / `"32px 16px"` / `"cover"`. A single length squares the tile,
 * as CSS renders it for the gradient washes this is used for.
 */
public fun revnixParseFillSize(css: String?): RevnixFillSize? {
    val trimmed = css?.trim()?.lowercase() ?: return null
    if (trimmed.isEmpty()) return null
    if (trimmed == "cover") return RevnixFillSize.Cover
    if (trimmed == "contain") return RevnixFillSize.Contain
    val parts = revnixSplitTopLevel(trimmed, ' ')
    val w = revnixParseLength(parts.firstOrNull()) ?: return null
    val h = if (parts.size > 1) revnixParseLength(parts[1]) ?: return null else w
    // A zero or negative tile would divide by zero when laying tiles out.
    if ((w.fraction <= 0.0 && w.px <= 0.0) || (h.fraction <= 0.0 && h.px <= 0.0)) return null
    return RevnixFillSize.Tile(w, h)
}

/**
 * Whether these vertices describe a CONVEX outline at a box of [width] × [height].
 *
 * Android clips a view to a path through [android.graphics.Outline], and an
 * Outline can only clip a convex path — the platform refuses a concave one
 * outright. Four of the five clip shapes the shipped library uses (the
 * triangle, the ticket notch and the two chevron rails, 9 of the 10 uses) are
 * convex and clip exactly; the 32-point starburst is not, and is reported as a
 * diagnostic rather than drawn as a rectangle pretending to be a star.
 *
 * The box matters, and defaulting it to a unit square would be a bug: the
 * ticket notch is authored as `calc(100% - 16px)`, so a 1×1 box resolves that
 * to -15 and turns a convex pentagon into nonsense. The default is a nominal
 * 100×100 — the same magnitude the px terms are written in — and the renderer
 * passes the view's REAL size once it has one.
 *
 * Convexity is the sign of the cross product at each vertex staying consistent
 * all the way round. Collinear vertices (a zero cross) are skipped rather than
 * treated as a reversal — calling those concave would lose a clip the platform
 * can draw.
 */
public fun List<RevnixPolygonPoint>.revnixIsConvex(
    width: Double = 100.0,
    height: Double = 100.0,
): Boolean {
    if (size < 3) return false
    val xs = map { it.x.resolvedAgainst(width) }
    val ys = map { it.y.resolvedAgainst(height) }
    var sign = 0
    for (i in indices) {
        val j = (i + 1) % size
        val k = (i + 2) % size
        val cross = (xs[j] - xs[i]) * (ys[k] - ys[j]) - (ys[j] - ys[i]) * (xs[k] - xs[j])
        // A near-zero cross is collinear, not a turn.
        if (kotlin.math.abs(cross) < 1e-9) continue
        val s = if (cross > 0) 1 else -1
        if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
}
