package com.shilapi.xcertplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/** Local framing changes only this secondary texture, never the main-screen surface. */
internal class LeapmotorClusterVideoTexture(
    context: Context,
    private val framing: LeapmotorClusterRoute.Framing,
    private val onSurface: (Surface?) -> Unit,
) : TextureView(context) {
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var output: Surface? = null

    init {
        isOpaque = false
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(sourceWidth.takeIf { it > 0 } ?: width.coerceAtLeast(1),
                    sourceHeight.takeIf { it > 0 } ?: height.coerceAtLeast(1))
                updateTransform()
                output = Surface(texture).also(onSurface)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = updateTransform()
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                onSurface(null)
                output?.release()
                output = null
                return true
            }
        }
    }

    fun setSourceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        sourceWidth = width
        sourceHeight = height
        updateTransform()
    }

    private fun updateTransform() {
        setTransform(LeapmotorClusterViewport.textureTransform(sourceWidth, sourceHeight, width, height, framing))
        invalidate()
    }
}
