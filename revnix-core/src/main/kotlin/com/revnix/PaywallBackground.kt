// The paywall screen background — the Kotlin half of the dashboard's model.
//
// A background is either the original CSS string (a colour or a gradient) or a
// layered spec: a ground colour/gradient, then a photo (fit, focal point,
// opacity, blur), then a scrim. [revnixBackgroundLayers] resolves either form
// to one paint list, mirroring revnix-app/src/lib/paywall-blocks/background.ts
// and the React SDK's blocks/background.ts — the same names, the same
// defaults, the same clamping.
//
// Two things this file exists to get right:
//
//  1. The ground field on the wire is `color`. It is NOT `ground` — that is
//     the name of the RESOLVED layer, and decoding it off the wire is what
//     rendered every edited paywall pure black. `ground` stays accepted so a
//     document published by a build that wrote it still opens.
//
//  2. Most shipped library backgrounds are CSS gradients, which the colour
//     parser rejects. They are parsed here into framework-free descriptors the
//     android module turns into shaders, so the ground paints as designed
//     rather than falling back to black.
//
// This file stays free of android.* on purpose: it is what the core module's
// unit tests can exercise without a device.

package com.revnix

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** How a background photo fills the screen. */
public enum class RevnixBackgroundFit { COVER, CONTAIN }

/** The photo layer. */
public data class RevnixBackgroundImage(
    /** An https URL the device can load. */
    public val url: String,
    public val fit: RevnixBackgroundFit = RevnixBackgroundFit.COVER,
    /**
     * Focal point in percent (0-100, 50/50 = centred): the part of the photo
     * that must survive a cover-crop.
     */
    public val focalX: Double = 50.0,
    public val focalY: Double = 50.0,
    /** 0-1. */
    public val opacity: Double = 1.0,
    /** Blur radius in px; null when no blur was asked for. */
    public val blur: Double? = null,
)

/** The scrim painted over the photo. */
public data class RevnixBackgroundOverlay(
    /** Any colour or gradient string. */
    public val fill: String,
    /** 0-1. */
    public val opacity: Double = 1.0,
)

/** The resolved paint list, bottom layer first. */
public data class RevnixBackgroundLayers(
    /** The ground fill (a colour or gradient string), or null for none. */
    public val ground: String? = null,
    public val image: RevnixBackgroundImage? = null,
    public val overlay: RevnixBackgroundOverlay? = null,
) {
    /**
     * True when the background paints nothing but a ground — the shape every
     * unedited paywall has, and the one that needs no extra layers at all.
     */
    public val isGroundOnly: Boolean get() = image == null && overlay == null
}

/** 0-100 (or absent) -> 0-1, clamped. */
private fun pct(value: Double?, fallback: Double): Double =
    if (value == null || value.isNaN() || value.isInfinite()) fallback
    else (value / 100).coerceIn(0.0, 1.0)

/** 0-100 (or absent) -> a clamped percentage. */
private fun coord(value: Double?): Double =
    if (value == null || value.isNaN() || value.isInfinite()) 50.0
    else value.coerceIn(0.0, 100.0)

/**
 * The ground paint of a background: `color`, or the legacy `ground`.
 *
 * Reading `color` FIRST is the fix: it is the only key the dashboard writes.
 */
public fun revnixBackgroundGround(background: JsonElement?): String? {
    if (background == null) return null
    background.revnixStringOrNull?.let { return it.ifEmpty { null } }
    val o = background as? JsonObject ?: return null
    val value = o["color"]?.revnixStringOrNull ?: o["ground"]?.revnixStringOrNull
    return value?.ifEmpty { null }
}

/**
 * The paint list for a background: ground, then photo, then scrim. Layers that
 * would draw nothing (no url, zero opacity) are dropped, so a legacy string
 * resolves to exactly one ground layer.
 */
public fun revnixBackgroundLayers(
    background: JsonElement?,
    /** Resolves palette tokens in the ground and overlay fills. */
    resolve: (String) -> String = { it },
): RevnixBackgroundLayers {
    val ground = revnixBackgroundGround(background)?.let(resolve)
    val o = background as? JsonObject ?: return RevnixBackgroundLayers(ground = ground)

    var image: RevnixBackgroundImage? = null
    (o["image"] as? JsonObject)?.let { i ->
        val url = i["url"]?.revnixStringOrNull?.ifEmpty { null }
        val opacity = pct(i["opacity"]?.revnixDoubleOrNull, 1.0)
        if (url != null && opacity > 0) {
            val blur = i["blur"]?.revnixDoubleOrNull
            image = RevnixBackgroundImage(
                url = url,
                fit = if (i["fit"]?.revnixStringOrNull == "contain") {
                    RevnixBackgroundFit.CONTAIN
                } else {
                    RevnixBackgroundFit.COVER
                },
                focalX = coord(i["focalX"]?.revnixDoubleOrNull),
                focalY = coord(i["focalY"]?.revnixDoubleOrNull),
                opacity = opacity,
                blur = if (blur != null && blur > 0) blur else null,
            )
        }
    }

    var overlay: RevnixBackgroundOverlay? = null
    (o["overlay"] as? JsonObject)?.let { v ->
        val fill = v["fill"]?.revnixStringOrNull?.ifEmpty { null }
        val opacity = pct(v["opacity"]?.revnixDoubleOrNull, 1.0)
        if (fill != null && opacity > 0) {
            overlay = RevnixBackgroundOverlay(fill = resolve(fill), opacity = opacity)
        }
    }

    return RevnixBackgroundLayers(ground = ground, image = image, overlay = overlay)
}

/**
 * The flat colour a gradient ground stands in for — what paints UNDER the
 * gradient, so a form this parser does not understand still shows a colour
 * from the design rather than black.
 *
 * It reads the LAST comma-separated layer, because in CSS the first-listed
 * layer paints on TOP: taking the first colour would answer with the
 * translucent accent glow the library's Spotlight and Corner halo presets
 * stack over their base, not with the base itself. Fully transparent stops
 * are skipped for the same reason — a scrim that fades to `…00` says nothing
 * about the ground under it.
 *
 * This is NOT what `@bg` resolves to. The dashboard answers that token with
 * the raw ground, which `color-mix()` cannot take when it is a gradient, so a
 * `@bg` tint over a gradient renders nothing in the builder — and must render
 * nothing here too, or the device stops matching the design.
 */
public fun revnixBackgroundBaseColor(ground: String?): String {
    val s = ground?.trim().orEmpty()
    if (s.isEmpty()) return "#000000"
    val bottom = revnixSplitTopLevel(s).lastOrNull() ?: s
    val colors = Regex("#[0-9a-fA-F]{3,8}\\b|rgba?\\([^)]*\\)", RegexOption.IGNORE_CASE)
        .findAll(bottom).map { it.value }.toList()
    if (colors.isEmpty()) return s
    return colors.firstOrNull { parseColor(it)?.let { c -> (c ushr 24) and 0xFF } != 0 }
        ?: colors.first()
}

// ——— CSS gradients ———

/** One colour stop: an ARGB colour and its position in 0-1. */
public data class RevnixGradientStop(public val color: Int, public val position: Double)

/** A parsed CSS gradient, in terms the renderer can turn into a shader. */
public sealed class RevnixGradient {
    public abstract val stops: List<RevnixGradientStop>

    /**
     * A linear gradient. [dirX]/[dirY] is the unit direction in screen
     * coordinates (x right, y down), scaled so its largest component is 1 —
     * which is what makes 135deg run corner to corner as CSS draws it.
     */
    public data class Linear(
        public val dirX: Double,
        public val dirY: Double,
        override val stops: List<RevnixGradientStop>,
    ) : RevnixGradient()

    /**
     * A radial gradient. [centerX]/[centerY] are 0-1 fractions of the box, and
     * [radius] is a fraction of the box's LARGER side. CSS gives an ellipse
     * with independent extents; taking the larger keeps a glow from stopping
     * short of the edge it was drawn to reach. The circular shape is a
     * deliberate approximation.
     */
    public data class Radial(
        public val centerX: Double,
        public val centerY: Double,
        public val radius: Double,
        override val stops: List<RevnixGradientStop>,
    ) : RevnixGradient()
}

/**
 * Splits on top-level commas only, so the commas inside `rgba(...)` and inside
 * a nested gradient's argument list do not tear an argument in half.
 */
/**
 * Whether a paint string's BOTTOM layer is a repeating pattern.
 *
 * A `repeating-*` gradient is a TEXTURE, and the colours inside it are stripe
 * colours rather than the surface's. When a build cannot draw one, painting a
 * colour lifted out of its arguments across the whole box is a WRONG answer
 * rather than a degraded one — the library's `repeating-linear-gradient(180deg,
 * #0E1B21 0 1px, @bg 1px 26px)` is a hairline every 26px, and its first colour
 * as a solid fill is a slab. Such a fill paints nothing instead.
 *
 * The bottom layer is the one that decides, so a pattern stacked over a real
 * ground (`repeating-…(…), #FBF3E4`) still falls back to that ground.
 */
public fun revnixIsRepeatingPattern(css: String?): Boolean {
    val s = css?.trim().orEmpty()
    if (s.isEmpty()) return false
    val layers = revnixSplitTopLevel(s)
    val bottom = layers.lastOrNull() ?: s
    return bottom.startsWith("repeating-", ignoreCase = true)
}

internal fun revnixSplitTopLevel(input: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in input.indices) {
        when (input[i]) {
            '(' -> depth++
            ')' -> if (depth > 0) depth--
            ',' -> if (depth == 0) {
                out.add(input.substring(start, i).trim())
                start = i + 1
            }
        }
    }
    input.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
    return out
}

/**
 * Parses the gradient forms the dashboard emits — `linear-gradient(Ndeg, ...)`
 * and `radial-gradient(RX% RY% at X% Y%, ...)` — plus the comma-separated
 * stacks of them the library's Spotlight and Corner halo presets use.
 *
 * Returns the layers BOTTOM FIRST, the reverse of CSS's own order (in CSS the
 * first-listed layer paints on top), so the result can be drawn in sequence.
 */
public fun revnixParseCssGradients(
    css: String,
    parse: (String) -> Int?,
): List<RevnixGradient> =
    revnixSplitTopLevel(css).mapNotNull { parseOneGradient(it, parse) }.reversed()

private class RawStop(val color: Int, val position: Double?)

private val STOP_POSITION = Regex("\\s(-?[\\d.]+%|0)\\s*$")

/**
 * One argument of a gradient's stop list, as the stop(s) it stands for.
 *
 * The positions are the trailing `<n>%` (or a unitless `0`); everything before
 * them is the colour, which may itself contain spaces (`rgba(0, 0, 0, 0.5)`).
 * CSS allows TWO positions on one stop — `@accent 0 22%` is the same colour at
 * both, the hard edge the library's progress bars and split panels are drawn
 * with — so this answers with a list rather than a single stop.
 */
private fun parseStops(raw: String, parse: (String) -> Int?): List<RawStop> {
    var body = raw.trim()
    if (body.isEmpty()) return emptyList()
    val positions = mutableListOf<Double>()
    while (positions.size < 2) {
        val match = STOP_POSITION.find(body) ?: break
        val text = match.groupValues[1]
        val isPercent = text.endsWith("%")
        val value = (if (isPercent) text.dropLast(1) else text).toDoubleOrNull()
        // The token comes off `body` either way. Leaving a position this build
        // could not read attached to the colour made the colour unparseable
        // too, which dropped the whole stop — and a gradient left with one stop
        // does not parse at all. A malformed position is worth losing; the stop
        // is not, so it falls through to the interpolated position instead.
        body = body.substring(0, match.range.first).trim()
        if (value == null) break
        positions.add(0, (if (isPercent) value / 100 else value).coerceIn(0.0, 1.0))
    }
    if (body.isEmpty()) return emptyList()
    val color = parse(body) ?: return emptyList()
    if (positions.isEmpty()) return listOf(RawStop(color, null))
    return positions.map { RawStop(color, it) }
}

/** Fills in the positions CSS would interpolate for stops that gave none. */
private fun stopPositions(stops: List<RawStop>): List<Double> {
    val out = stops.map { it.position }.toMutableList()
    if (out.first() == null) out[0] = 0.0
    if (out.last() == null) out[out.size - 1] = 1.0
    for (i in 1 until out.size - 1) {
        if (out[i] != null) continue
        var next = i + 1
        while (next < out.size && out[next] == null) next++
        val from = out[i - 1]!!
        val to = out[next]!!
        val span = next - (i - 1)
        for (k in i until next) out[k] = from + (to - from) * ((k - (i - 1)).toDouble() / span)
    }
    // Shader stops must be non-decreasing.
    var previous = 0.0
    return out.map { value ->
        val v = (value ?: previous).coerceIn(0.0, 1.0)
        val result = if (v < previous) previous else v
        previous = result
        result
    }
}

private fun parseOneGradient(raw: String, parse: (String) -> Int?): RevnixGradient? {
    val s = raw.trim()
    val open = s.indexOf('(')
    if (open < 0 || !s.endsWith(")")) return null
    val name = s.substring(0, open).trim().lowercase()
    val args = revnixSplitTopLevel(s.substring(open + 1, s.length - 1))
    if (args.isEmpty()) return null

    when (name) {
        "linear-gradient" -> {
            var angle = 180.0
            var first = 0
            val head = args[0].trim().lowercase()
            val deg = Regex("^(-?[\\d.]+)deg$").find(head)
            if (deg != null) {
                angle = deg.groupValues[1].toDoubleOrNull() ?: 180.0
                first = 1
            } else if (head.startsWith("to ")) {
                angleForKeyword(head.removePrefix("to ").trim())?.let { angle = it }
                first = 1
            }
            val stops = args.drop(first).flatMap { parseStops(it, parse) }
            if (stops.size < 2) return null
            // CSS measures clockwise from "to top", so the gradient runs along
            // (sin a, -cos a) in screen coordinates.
            val radians = Math.toRadians(angle)
            var dx = sin(radians)
            var dy = -cos(radians)
            val longest = max(abs(dx), abs(dy))
            if (longest > 0.0001) {
                dx /= longest
                dy /= longest
            }
            // sin(PI) is 1.2e-16, not 0, so an axis-aligned gradient carries a
            // hair of the other axis. Harmless to a shader, but snapping it
            // keeps the descriptor exact and comparable.
            if (abs(dx) < 1e-9) dx = 0.0
            if (abs(dy) < 1e-9) dy = 0.0
            val positions = stopPositions(stops)
            return RevnixGradient.Linear(
                dirX = dx,
                dirY = dy,
                stops = stops.mapIndexed { i, stop -> RevnixGradientStop(stop.color, positions[i]) },
            )
        }

        "radial-gradient" -> {
            var centerX = 0.5
            var centerY = 0.5
            var radius = 0.5
            var first = 0
            val head = args[0].trim()
            val shape = Regex(
                "^([\\d.]+)%\\s+([\\d.]+)%(?:\\s+at\\s+([\\d.]+)%\\s+([\\d.]+)%)?$",
                RegexOption.IGNORE_CASE,
            ).find(head)
            val atOnly = Regex("^at\\s+([\\d.]+)%\\s+([\\d.]+)%$", RegexOption.IGNORE_CASE).find(head)
            if (shape != null) {
                val rx = shape.groupValues[1].toDoubleOrNull() ?: 50.0
                val ry = shape.groupValues[2].toDoubleOrNull() ?: 50.0
                radius = (max(rx, ry) / 100).coerceIn(0.05, 4.0)
                centerX = (shape.groupValues[3].toDoubleOrNull() ?: 50.0) / 100
                centerY = (shape.groupValues[4].toDoubleOrNull() ?: 50.0) / 100
                first = 1
            } else if (atOnly != null) {
                centerX = (atOnly.groupValues[1].toDoubleOrNull() ?: 50.0) / 100
                centerY = (atOnly.groupValues[2].toDoubleOrNull() ?: 50.0) / 100
                first = 1
            }
            val stops = args.drop(first).flatMap { parseStops(it, parse) }
            if (stops.size < 2) return null
            val positions = stopPositions(stops)
            return RevnixGradient.Radial(
                centerX = centerX,
                centerY = centerY,
                radius = radius,
                stops = stops.mapIndexed { i, stop -> RevnixGradientStop(stop.color, positions[i]) },
            )
        }

        else -> return null
    }
}

private fun angleForKeyword(keyword: String): Double? =
    when (keyword.replace(Regex("\\s+"), " ")) {
        "top" -> 0.0
        "right" -> 90.0
        "bottom" -> 180.0
        "left" -> 270.0
        "top right", "right top" -> 45.0
        "bottom right", "right bottom" -> 135.0
        "bottom left", "left bottom" -> 225.0
        "top left", "left top" -> 315.0
        else -> null
    }

// The same read-never-throw accessors PaywallBlocks.kt uses: one unexpected
// field type must cost that field, never the screen.

private val JsonElement.revnixStringOrNull: String?
    get() = runCatching { jsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content

private val JsonElement.revnixDoubleOrNull: Double?
    get() = runCatching { jsonPrimitive.doubleOrNull }.getOrNull()
