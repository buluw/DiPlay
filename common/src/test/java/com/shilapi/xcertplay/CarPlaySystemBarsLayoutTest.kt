package com.shilapi.xcertplay

import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlaySystemBarsLayoutTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var root: FrameLayout

    @Before fun start() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        root = invoke("buildContentView") as FrameLayout
        set("hideTopBar", false)
        set("hideBottomBar", false)
    }

    @After fun stop() {
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
    }

    @Test fun leapmotorBarsBoundVideoGesturesAndMenusToTheSame1920By880Area() {
        applyInsets(80, 120)
        layout(1920, 1080)
        assertViewport(0, 80, 1920, 960)
        for (name in listOf("settingsMenu", "safeAreaEditor")) {
            val view = field(name) as View
            view.visibility = View.VISIBLE
            layout(1920, 1080)
            assertEquals(80, view.top)
            assertEquals(960, view.bottom)
            view.visibility = View.GONE
        }
        val preparation = field("connectionPanel") as View
        assertEquals(0, preparation.paddingTop)
        assertEquals(0, preparation.paddingBottom)
    }

    @Test fun alreadyInsetWindowsAndRepeatedInsetsNeverSubtractTheBarsTwice() {
        repeat(3) { applyInsets(80, 120); layout(1920, 1080) }
        assertViewport(0, 80, 1920, 960)
        // If a parent/window has already reserved the bars, its remaining insets are zero.
        applyInsets(0, 0)
        layout(1920, 880)
        assertViewport(0, 0, 1920, 880)
    }

    @Test fun eachBarCanBeHiddenAndSideNavigationBarsAreAlsoRespected() {
        for (hideTop in listOf(false, true)) {
            for (hideBottom in listOf(false, true)) {
                set("hideTopBar", hideTop)
                set("hideBottomBar", hideBottom)
                applyInsets(80, 120)
                layout(1920, 1080)
                assertViewport(0, if (hideTop) 0 else 80, 1920, if (hideBottom) 1080 else 960)
            }
        }
        set("hideBottomBar", false)
        applyInsets(0, 0, right = 120)
        layout(1920, 1080)
        assertViewport(0, 0, 1800, 1080)
    }

    @Test fun harmanFullscreenOverrideDoesNotReserveBarsFromTheNormalSettings() {
        set("harmanFullscreenEnabled", true)
        applyInsets(80, 120)
        layout(1920, 1080)
        assertViewport(0, 0, 1920, 1080)
        set("harmanFullscreenEnabled", false)
        applyInsets(80, 120)
        layout(1920, 1080)
        assertViewport(0, 80, 1920, 960)
    }

    @Test fun surfaceViewFallbackKeepsTheSameInsetViewport() {
        applyInsets(80, 120)
        layout(1920, 1080)
        val texture = field("videoView") as TextureView
        activity.javaClass.getDeclaredMethod("useFallbackVideoSurface", TextureView::class.java)
            .apply { isAccessible = true }.invoke(activity, texture)
        layout(1920, 1080)
        assertViewport(0, 80, 1920, 960)
        assertNotNull(field("fallbackVideoView"))
    }

    @Test fun touchesUseTheInsetVideoOriginAndNeverReachCarPlayFromTheDock() {
        applyInsets(80, 120)
        layout(1920, 1080)
        (field("connectionPanel") as View).visibility = View.GONE
        val controller = mock(CarPlayController::class.java)
        set("controller", controller)
        set("sessionDisplay", CarPlaySessionDisplay(1920, 880, Surface.ROTATION_0,
            false, false, 1920, 880))
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 960f, 520f, 0)
        try {
            assertTrue(root.dispatchTouchEvent(event))
            val invocation = mockingDetails(controller).invocations.single { it.method.name == "sendTouch" }
            @Suppress("UNCHECKED_CAST")
            val contacts = invocation.arguments[0] as List<AirPlayContact>
            assertEquals(1, contacts.size)
            // The center is y=520 on screen, y=440 within the 880px CarPlay viewport.
            assertEquals(0.5, contacts.single().y, 0.001)
        } finally { event.recycle() }
        val release = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 960f, 520f, 0)
        try { root.dispatchTouchEvent(release) } finally { release.recycle() }
        clearInvocations(controller)
        val dock = MotionEvent.obtain(2, 2, MotionEvent.ACTION_DOWN, 960f, 1020f, 0)
        try { root.dispatchTouchEvent(dock) } finally { dock.recycle() }
        verifyNoInteractions(controller)
    }

    @Test fun portraitSidePanelDragTracksTheFingerAndSavesTheShareWithTopInsets() {
        assertSidePanelTabDrag(portrait = true)
    }

    @Test fun rightSidePanelDragTracksTheFingerAndSavesTheShareWithLeftInsets() {
        assertSidePanelTabDrag(portrait = false)
    }

    @Test fun leftSidePanelDragTracksTheFingerAndSavesTheShareWithLeftInsets() {
        assertSidePanelTabDrag(portrait = false, rightHandDrive = true)
    }

    private fun assertSidePanelTabDrag(portrait: Boolean, rightHandDrive: Boolean = false) {
        val width = if (portrait) 900 else 1800
        val height = if (portrait) 1800 else 900
        val videoWidth = if (portrait) 900 else 1600
        val videoHeight = if (portrait) 1600 else 900
        applyInsets(if (portrait) 80 else 0, if (portrait) 120 else 0,
            right = if (portrait) 0 else 120, left = if (portrait) 0 else 80)
        val areas = CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, false), CarPlayViewAreas.Screen(1440, 2560, true)),
            CarPlayDock.AUTOMATIC, { null }, portrait, sidePanel = true, rightHandDrive = rightHandDrive)!!
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        set("controller", controller)
        set("sessionDisplay", CarPlaySessionDisplay(2560, 2560,
            if (portrait) Surface.ROTATION_90 else Surface.ROTATION_0,
            true, true, videoWidth, videoHeight, areas))
        SidePanelSettings.setSixths(activity, 4)
        (field("connectionPanel") as View).visibility = View.GONE
        layout(width, height)
        activity.javaClass.getDeclaredMethod("placeSidePanelTab", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, videoWidth, videoHeight)
        layout(width, height)
        val video = field("videoView") as View
        val tab = field("sidePanelTab") as View
        val panel = field("sidePanel") as View
        assertEquals(videoWidth, video.width)
        assertEquals(videoHeight, video.height)
        assertEquals(View.VISIBLE, tab.visibility)
        touchRoot(MotionEvent.ACTION_DOWN, tab.x + tab.width / 2f, tab.y + tab.height / 2f)
        val fingerX = video.x + if (portrait) 450f else 900f
        val fingerY = video.y + if (portrait) 900f else 450f
        touchRoot(MotionEvent.ACTION_MOVE, fingerX, fingerY)
        layout(width, height)
        val edge = if (portrait) panel.top else if (rightHandDrive) panel.right else panel.left
        assertEquals("The visible panel edge must stay under the finger",
            if (portrait) fingerY.toInt() else fingerX.toInt(), edge)
        touchRoot(MotionEvent.ACTION_UP, fingerX, fingerY)
        assertEquals("System bars must not change the selected CarPlay share", 3, SidePanelSettings.sixths(activity))
        val target = areas.sidePanel(portrait, 3)!!
        assertEquals(target, areas.current)
        verify(controller).showViewArea(target)
    }

    private fun touchRoot(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, action.toLong(), action, x, y, 0)
        try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    @Test fun keyboardResizesOnlyTheMenuAndKeepsFocusedInputsAndSaveReachable() {
        val menu = field("settingsMenu") as FrameLayout
        menu.visibility = View.VISIBLE
        (field("mfiRemoteFields") as View).visibility = View.VISIBLE
        applyInsets(80, 120)
        layout(1920, 1080)
        val input = field("remoteMfiServerInput") as View
        val panel = menu.getChildAt(0) as FrameLayout
        val scroll = descendants(panel).filterIsInstance<ScrollView>().single()
        val originalScrollHeight = scroll.height
        assertTrue(input.requestFocus())
        layout(1920, 1080)
        repeat(3) { applyInsets(80, 120, ime = 480); layout(1920, 1080) }
        assertViewport(0, 80, 1920, 960)
        assertPanelFits(menu, 80, 600)
        assertTrue(scroll.height in 1 until originalScrollHeight)
        assertTrue(boundsInRoot(scroll).bottom <= 600)
        assertTrue(boundsInRoot(input).bottom <= 600)
        scroll.fullScroll(View.FOCUS_DOWN)
        val save = descendants(panel).filterIsInstance<TextView>()
            .single { it.text == activity.getString(R.string.save_and_reconnect) }
        assertTrue(boundsInRoot(save).top >= 80)
        assertTrue(boundsInRoot(save).bottom <= 600)
        applyInsets(80, 120)
        layout(1920, 1080)
        assertPanelFits(menu, 80, 960)
        assertEquals(originalScrollHeight, scroll.height)
        assertViewport(0, 80, 1920, 960)
    }

    @Test fun rebuiltMenuAppliesTheRemainingKeyboardInsetWithoutCountingTheDockTwice() {
        applyInsets(80, 120, ime = 480)
        layout(1920, 1080)
        val previous = field("settingsMenu")
        invoke("openSettingsMenu")
        set("hideTopBar", false)
        set("hideBottomBar", false)
        val menu = field("settingsMenu") as FrameLayout
        assertNotSame(previous, menu)
        // Rebuilding uses the retained IME before the next platform Insets dispatch.
        assertEquals(360, menu.paddingBottom)
        applyInsets(80, 120, ime = 480)
        layout(1920, 1080)
        assertEquals(360, menu.paddingBottom)
        assertPanelFits(menu, 80, 600)
        assertViewport(0, 80, 1920, 960)
    }

    @Test fun safeAreaActionsAvoidTheKeyboardWithoutChangingTheEditorCanvas() {
        applyInsets(80, 120, ime = 480)
        layout(1920, 1080)
        invoke("openSafeAreaEditor")
        applyInsets(80, 120, ime = 480)
        layout(1920, 1080)
        val overlay = field("safeAreaEditor") as FrameLayout
        val editor = field("safeAreaEditorView") as View
        val controls = overlay.getChildAt(2)
        assertEquals(Rect(0, 80, 1920, 960), boundsInRoot(editor))
        assertEquals(600, boundsInRoot(controls).bottom)
        applyInsets(80, 120)
        layout(1920, 1080)
        assertEquals(960, boundsInRoot(controls).bottom)
        invoke("closeSafeAreaEditor")
        applyInsets(80, 120, ime = 480)
        layout(1920, 1080)
        val menu = field("settingsMenu") as FrameLayout
        assertEquals(View.VISIBLE, menu.visibility)
        assertPanelFits(menu, 80, 600)
        assertViewport(0, 80, 1920, 960)
    }

    private fun assertPanelFits(menu: ViewGroup, top: Int, bottom: Int) {
        val panel = boundsInRoot(menu.getChildAt(0))
        assertTrue(panel.height() > 0)
        assertTrue(panel.top >= top)
        assertTrue(panel.bottom <= bottom)
        assertTrue(kotlin.math.abs(panel.exactCenterY() - (top + bottom) / 2f) <= 1f)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()

    private fun boundsInRoot(view: View) = Rect(view.scrollX, view.scrollY,
        view.scrollX + view.width, view.scrollY + view.height).also {
        root.offsetDescendantRectToMyCoords(view, it)
    }

    private fun applyInsets(top: Int, bottom: Int, right: Int = 0, ime: Int = 0, left: Int = 0) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(left, 0, right, bottom))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        } else {
            // A synthetic IME type cannot survive conversion to API 28's platform WindowInsets.
            // Feed the same implementation used by the root listener its compat input directly;
            // children still receive the normal inset platform dispatch.
            val remaining = activity.javaClass.getDeclaredMethod("applyContentInsets", FrameLayout::class.java,
                WindowInsetsCompat::class.java).apply { isAccessible = true }
                .invoke(activity, root, insets) as WindowInsetsCompat
            for (index in 0 until root.childCount) {
                ViewCompat.dispatchApplyWindowInsets(root.getChildAt(index), remaining)
            }
        }
    }

    private fun layout(width: Int, height: Int) {
        fun invalidate(view: View) {
            view.forceLayout()
            if (view is ViewGroup) for (index in 0 until view.childCount) invalidate(view.getChildAt(index))
        }
        repeat(2) {
            invalidate(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
    }

    private fun assertViewport(left: Int, top: Int, right: Int, bottom: Int) {
        for (name in listOf("videoView", "gestureOverlay", "connectionPanel")) {
            val view = field(name) as View
            assertEquals(name, listOf(left, top, right, bottom), listOf(view.left, view.top, view.right, view.bottom))
        }
    }

    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
    private fun invoke(name: String) = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
}
