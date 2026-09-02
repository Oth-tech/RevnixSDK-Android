// The screen background's paint layers, as Android drawables and views.
//
// The core module parses a CSS background into framework-free descriptors
// (see revnix-core PaywallBackground.kt); this file is the half that needs
// android.graphics. Keeping the split means the parsing — where the bugs
// live — is unit-testable without a device.

package com.revnix.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.revnix.RevnixBackgroundFit
import com.revnix.RevnixBackgroundImage
import com.revnix.RevnixGradient
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Paints a stack of parsed CSS gradients, bottom layer first.
 *
 * A single Drawable rather than one view per gradient: the stops are known up
 * front, so the whole ground is one draw pass however many layers the preset
 * stacks (the library's Spotlight and Corner halo each stack two).
 */
internal class RevnixGradientDrawable(
    private val gradients: List<RevnixGradient>,
    /** Corner radius in px. A block fill has to clip to the box it fills. */
    private val cornerRadius: Float = 0f,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shaders: List<Shader> = emptyList()
    private var built = Rect()

    private fun build(bounds: Rect) {
        if (bounds == built && shaders.isNotEmpty()) return
        built = Rect(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            shaders = emptyList()
            return
        }
        shaders = gradients.mapNotNull { gradient ->
            val colors = gradient.stops.map { it.color }.toIntArray()
            val positions = gradient.stops.map { it.position.toFloat() }.toFloatArray()
            if (colors.size < 2) return@mapNotNull null
            when (gradient) {
                is RevnixGradient.Linear -> {
                    // The direction is a unit vector scaled so its largest
                    // component is 1, so half of it from the centre reaches the
                    // box edge on that axis — the CSS gradient line.
                    val cx = bounds.left + w / 2f
                    val cy = bounds.top + h / 2f
                    val dx = (gradient.dirX * w / 2f).toFloat()
                    val dy = (gradient.dirY * h / 2f).toFloat()
                    LinearGradient(
                        cx - dx, cy - dy, cx + dx, cy + dy,
                        colors, positions, Shader.TileMode.CLAMP,
                    )
                }
                is RevnixGradient.Radial -> {
                    val radius = (gradient.radius * max(w, h)).toFloat()
                    if (radius <= 0f) return@mapNotNull null
                    RadialGradient(
                        bounds.left + (gradient.centerX * w).toFloat(),
                        bounds.top + (gradient.centerY * h).toFloat(),
                        radius,
                        colors, positions, Shader.TileMode.CLAMP,
                    )
                }
            }
        }
    }

    override fun draw(canvas: Canvas) {
        build(bounds)
        rect.set(bounds)
        // Nothing is painted UNDER the layers: a gradient that fades through a
        // translucent stop is drawn over the screen's art precisely so it shows
        // through, and a flat base would make it a solid block.
        for (shader in shaders) {
            paint.shader = shader
            fill(canvas, paint)
        }
        paint.shader = null
    }

    /** A rounded box when the design asked for one, a plain rect otherwise —
     *  `drawRoundRect` with a zero radius still costs a path. */
    private fun fill(canvas: Canvas, paint: Paint) {
        if (cornerRadius > 0f) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        } else {
            canvas.drawRect(rect, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The background photo as a view.
 *
 * Image loading is otherwise the host app's job in this SDK — it ships no
 * image library — but a background the designer chose is not optional
 * decoration, so the same plain HttpURLConnection + BitmapFactory fetch the
 * legacy paywall uses for its hero is reused here. A failed load leaves the
 * ground and scrim in place rather than blacking out the screen.
 */
internal class RevnixBackgroundPhotoView(
    context: Context,
    private val spec: RevnixBackgroundImage,
) : ImageView(context) {

    private var thread: Thread? = null

    init {
        scaleType = if (spec.fit == RevnixBackgroundFit.CONTAIN) {
            ScaleType.FIT_CENTER
        } else {
            // MATRIX lets the focal point decide which part of a cover-crop
            // survives; CENTER_CROP would always keep the middle.
            ScaleType.MATRIX
        }
        alpha = spec.opacity.toFloat()
        isClickable = false
        isFocusable = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            spec.blur?.let { radius ->
                if (radius > 0) {
                    setRenderEffect(
                        RenderEffect.createBlurEffect(
                            radius.toFloat(), radius.toFloat(), Shader.TileMode.CLAMP,
                        ),
                    )
                    // A blurred view bleeds its transparent edge inward, which
                    // reads as a bright rim over the ground. Scaling past the
                    // edges hides it.
                    scaleX = 1.1f
                    scaleY = 1.1f
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (drawable != null || thread != null) return
        val url = spec.url
        thread = Thread {
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
            if (bitmap != null) post { apply(bitmap) }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun onDetachedFromWindow() {
        thread?.interrupt()
        thread = null
        super.onDetachedFromWindow()
    }

    private fun apply(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        placeCover()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placeCover()
    }

    /**
     * Cover-fit geometry with a focal point — the same rule CSS applies for
     * `object-position: X% Y%`: the X% point of the image aligns to the X%
     * point of the box, clamped so no edge shows.
     */
    private fun placeCover() {
        if (scaleType != ScaleType.MATRIX) return
        val d = drawable ?: return
        val boxW = width.toFloat()
        val boxH = height.toFloat()
        val srcW = d.intrinsicWidth.toFloat()
        val srcH = d.intrinsicHeight.toFloat()
        if (boxW <= 0f || boxH <= 0f || srcW <= 0f || srcH <= 0f) return
        val scale = max(boxW / srcW, boxH / srcH)
        val drawnW = srcW * scale
        val drawnH = srcH * scale
        val left = place(boxW, drawnW, spec.focalX)
        val top = place(boxH, drawnH, spec.focalY)
        imageMatrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(left, top)
        }
    }

    private fun place(box: Float, drawn: Float, pct: Double): Float {
        val offset = (box - drawn) * (pct / 100).toFloat()
        return offset.coerceIn(minOf(0f, box - drawn), max(0f, box - drawn))
    }
}

/** A plain fill view for the scrim: a solid colour or a gradient stack. */
internal fun revnixScrimView(
    context: Context,
    solid: Int?,
    gradients: List<RevnixGradient>,
    opacity: Double,
): View = View(context).apply {
    if (gradients.isNotEmpty()) {
        background = RevnixGradientDrawable(gradients)
    } else if (solid != null) {
        setBackgroundColor(solid)
    }
    alpha = opacity.toFloat()
    isClickable = false
    isFocusable = false
}

/** Fills its parent FrameLayout. */
internal fun revnixFillParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
    FrameLayout.LayoutParams.MATCH_PARENT,
    FrameLayout.LayoutParams.MATCH_PARENT,
)
