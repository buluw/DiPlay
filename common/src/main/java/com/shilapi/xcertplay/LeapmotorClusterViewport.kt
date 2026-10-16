package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.SafeAreaRect

/** Encode a complete map for the visible window, instead of hiding most of a wide map behind OEM UI. */
internal object LeapmotorClusterViewport {
    /** Only the explicitly selected 1920x720 Leapmotor canvas has a measured default. */
    fun defaultRect(width: Int, height: Int): SafeAreaRect =
        if (width == 1920 && height == 720) rightSquare(width, height)
        else SafeAreaRect(0, 0, width, height)

    fun rightSquare(width: Int, height: Int): SafeAreaRect {
        require(width > 0 && height > 0)
        val side = minOf(width, height)
        return SafeAreaRect(width - side, 0, width, side)
    }

    fun streamConfig(rect: SafeAreaRect, scale: Int, content: CarPlayClusterDisplay.Content) =
        CarPlayClusterDisplay.config(rect.width, rect.height, scalePercent = scale, content = content,
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT)

    /** Fit the entire main picture; never crop away its car marker, turn card or lane guidance. */
    fun fit(rect: SafeAreaRect, aspect: Double?): SafeAreaRect {
        if (aspect == null || !aspect.isFinite() || aspect <= 0) return rect
        val width = minOf(rect.width, (rect.height * aspect).toInt()).coerceAtLeast(1)
        val height = minOf(rect.height, (width / aspect).toInt()).coerceAtLeast(1)
        val left = rect.left + (rect.width - width) / 2
        val top = rect.top + (rect.height - height) / 2
        return SafeAreaRect(left, top, left + width, top + height)
    }

    /** Source-pixel crop, independent of the destination's position on the HDMI canvas. */
    fun sourceCrop(sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int,
        framing: LeapmotorClusterRoute.Framing): RectF {
        require(sourceWidth > 0 && sourceHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val crop = framing.normalized()
        if (!crop.crop) return RectF(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
        val aspect = viewWidth.toFloat() / viewHeight
        val width = minOf(sourceWidth.toFloat(), sourceHeight * aspect) * 100f / crop.zoom
        val height = width / aspect
        val left = (sourceWidth * (crop.centerX / 100f) - width / 2).coerceIn(0f, (sourceWidth - width).coerceAtLeast(0f))
        val top = (sourceHeight * (crop.centerY / 100f) - height / 2).coerceIn(0f, (sourceHeight - height).coerceAtLeast(0f))
        return RectF(left, top, left + width, top + height)
    }

    /** TextureView's default texture spans its view; map the selected source region onto that view. */
    fun textureTransform(sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int,
        framing: LeapmotorClusterRoute.Framing): Matrix {
        val result = Matrix()
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return result
        val source = sourceCrop(sourceWidth, sourceHeight, viewWidth, viewHeight, framing)
        source.set(source.left * viewWidth / sourceWidth, source.top * viewHeight / sourceHeight,
            source.right * viewWidth / sourceWidth, source.bottom * viewHeight / sourceHeight)
        result.setRectToRect(source, RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()), Matrix.ScaleToFit.FILL)
        return result
    }
}

/** Keep the map output and local overlays inside the calibrated physical display region. */
internal class LeapmotorClusterViewportFrame(
    context: Context,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val viewport: SafeAreaRect,
    content: View,
) : ViewGroup(context) {
    private val backdrop = Paint().apply { color = Color.BLACK }
    init { setWillNotDraw(false); addView(content) }
    var mirrorAspect: Double? = null
        set(value) { field = value; requestLayout() }

    private fun target(width: Int, height: Int) =
        AirPlaySafeArea.scaleRect(LeapmotorClusterViewport.fit(viewport, mirrorAspect), sourceWidth, sourceHeight,
            width.coerceAtLeast(1), height.coerceAtLeast(1))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = AirPlaySafeArea.scaleRect(viewport, sourceWidth, sourceHeight,
            width.coerceAtLeast(1), height.coerceAtLeast(1))
        // Letterboxing covers only the confirmed map window, never the OEM instruments.
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), backdrop)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val rect = target(width, height)
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rect = target(width, height)
        getChildAt(0).layout(rect.left, rect.top, rect.right, rect.bottom)
    }
}
