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
            activity.finish()
        }
        RuntimeEnvironment.setQualifiers("en")
        RuntimeEnvironment.setFontScale(1f)
    }
}
