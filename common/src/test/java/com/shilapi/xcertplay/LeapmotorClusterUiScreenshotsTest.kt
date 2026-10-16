package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeapmotorClusterUiScreenshotsTest {
    @Test fun selectionCardFitsCompactFullAndRtlAtLargeFonts() {
        for ((locale, width) in listOf("zh-rCN" to 600, "zh-rCN" to 1280, "ar" to 600)) {
            RuntimeEnvironment.setQualifiers(locale + "-w" + width + "dp-h960dp-mdpi")
            RuntimeEnvironment.setFontScale(1.5f)
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            activity.setTheme(android.R.style.Theme_Material_NoActionBar)
            val displayId = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h720dp")
            val display = activity.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
            LeapmotorClusterRoute.save(activity, display)
            LeapmotorClusterRoute.saveViewport(activity, LeapmotorClusterRoute.identity(display),
                com.shilapi.xcertplay.airplay.SafeAreaRect(1200, 0, 1920, 720))
            LeapmotorClusterRoute.saveFraming(activity, LeapmotorClusterRoute.identity(display),
                LeapmotorClusterRoute.Framing(crop = true))
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(android.graphics.Color.rgb(15, 23, 42))
                layoutDirection = if (locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "leapmotorClusterControls",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
            val scroll = ScrollView(activity).apply {
                setBackgroundColor(android.graphics.Color.rgb(15, 23, 42))
                addView(content)
            }
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, width, 960)
            assertTrue(content.getChildAt(0).height >= 60)
            assertTrue(content.getChildAt(0).width <= width)
            val button = content.getChildAt(0) as android.widget.TextView
            assertTrue(button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            assertTrue(content.height <= 960)
            val bitmap = Bitmap.createBitmap(width, 960, Bitmap.Config.ARGB_8888)
            scroll.draw(Canvas(bitmap))
            val directory = File("build/reports/leapmotor-cluster-ui").apply { mkdirs() }
            File(directory, locale + "-" + width + "-font1.5.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            val before = LeapmotorClusterRoute.configuration(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "openLeapmotorClusterViewportEditor")
            val dialog = ReflectionHelpers.getField<android.app.Dialog>(activity, "clusterSafeAreaDialog")
            assertTrue(dialog.isShowing)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val preview = dialog.window!!.decorView
            assertTrue(preview.isAttachedToWindow)
            val dialogWidth = (width * 0.9f).toInt()
            val dialogHeight = (960 * 0.85f).toInt()
            preview.measure(View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dialogHeight, View.MeasureSpec.EXACTLY))
            preview.layout(0, 0, dialogWidth, dialogHeight)
            val previewBitmap = Bitmap.createBitmap(dialogWidth, dialogHeight, Bitmap.Config.ARGB_8888)
            preview.draw(Canvas(previewBitmap))
            File(directory, locale + "-" + width + "-calibration-font1.5.png").outputStream().use {
                assertTrue(previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            previewBitmap.recycle()
            dialog.dismiss()
            assertEquals(before, LeapmotorClusterRoute.configuration(activity))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "openLeapmotorClusterFramingEditor")
            val framingDialog = ReflectionHelpers.getField<android.app.Dialog>(activity, "clusterSafeAreaDialog")
            assertTrue(framingDialog.isShowing)
            // Dialog.show posts window attachment; ScrollView indicators need its AttachInfo to draw.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val framingView = framingDialog.window!!.decorView
            assertTrue(framingView.isAttachedToWindow)
            framingView.measure(View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dialogHeight, View.MeasureSpec.EXACTLY))
            framingView.layout(0, 0, dialogWidth, dialogHeight)
            val framingBitmap = Bitmap.createBitmap(dialogWidth, dialogHeight, Bitmap.Config.ARGB_8888)
            framingView.draw(Canvas(framingBitmap))
            File(directory, locale + "-" + width + "-framing-font1.5.png").outputStream().use {
                assertTrue(framingBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            framingBitmap.recycle()
            framingDialog.dismiss()
            assertEquals(before, LeapmotorClusterRoute.configuration(activity))
            activity.finish()
            LeapmotorClusterRoute.save(activity, null)
            // Presentation window teardown is asynchronous in Robolectric. Keep this display
            // alive until the sandbox resets instead of removing it under queued relayouts.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        RuntimeEnvironment.setQualifiers("en")
        RuntimeEnvironment.setFontScale(1f)
    }
}
