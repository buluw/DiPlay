package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class LeapmotorClusterRouteTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun selectionIsExplicitAndNeverUsesTheMainDisplay() {
        val id = ShadowDisplayManager.addDisplay("w1760dp-h720dp")
        val manager = context.getSystemService(DisplayManager::class.java)
        assertFalse(LeapmotorClusterRoute.configured(context))
        assertNull(LeapmotorClusterRoute.findDisplay(context))
        assertTrue(LeapmotorClusterRoute.candidates(context).none { it.displayId == Display.DEFAULT_DISPLAY })
        LeapmotorClusterRoute.save(context, manager.getDisplay(id))
        assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        assertFalse(AdbClusterRouter.enabled(context))
        LeapmotorClusterRoute.save(context, null)
        assertNull(LeapmotorClusterRoute.findDisplay(context))
    }

    @Test fun disappearanceDoesNotFallBackToAnotherPanel() {
        val id = ShadowDisplayManager.addDisplay("w1760dp-h720dp")
        val manager = context.getSystemService(DisplayManager::class.java)
        LeapmotorClusterRoute.save(context, manager.getDisplay(id))
        ShadowDisplayManager.removeDisplay(id)
        ShadowDisplayManager.addDisplay("w1920dp-h720dp")
        assertTrue(LeapmotorClusterRoute.configured(context))
        assertNull(ClusterMapPresentation.findDisplay(context))
        assertFalse(AdbClusterRouter.enabled(context))
    }

    @Test fun reusedIdChangedGeometryAndPrivateScreensAreRejected() {
        val key = LeapmotorClusterRoute.Identity(2, "Cluster", 1760, 720, 0)
        assertTrue(key.accepts(key))
        assertFalse(key.accepts(key.copy(name = "Passenger")))
        assertFalse(key.accepts(key.copy(width = 1920)))
        assertFalse(key.accepts(key.copy(id = 1)))
        assertFalse(key.copy(id = 0).let { it.accepts(it) })
        assertFalse(key.copy(flags = Display.FLAG_PRIVATE).let { it.accepts(it) })
    }

    @Test fun projectionDoesNotTakeFocusOrTouchFromTheMainScreen() {
        val id = ShadowDisplayManager.addDisplay("w1760dp-h720dp")
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        LeapmotorClusterRoute.save(context, display)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val presentation = ClusterMapPresentation(activity.get(), display) {}
        try {
            presentation.show()
            val flags = presentation.window!!.attributes.flags
            assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        } finally {
            presentation.dismiss()
            activity.pause().stop().destroy()
        }
    }

    @Test fun missingSelectedPanelDoesNotNegotiateAVirtualFallback() {
        val id = ShadowDisplayManager.addDisplay("w1760dp-h720dp")
        LeapmotorClusterRoute.save(context, context.getSystemService(DisplayManager::class.java).getDisplay(id))
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        ShadowDisplayManager.removeDisplay(id)
        val activity = org.robolectric.Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val config = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any?>(activity, "clusterDisplayConfig")
            assertNull(config)
        } finally {
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                org.robolectric.util.ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(activity, name).shutdownNow()
            }
        }
    }
}
