package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.view.Surface
import android.view.View
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class LeapmotorClusterViewportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val right = SafeAreaRect(1020, 24, 1900, 648)

    private fun select(): LeapmotorClusterRoute.Identity {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp")
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        LeapmotorClusterRoute.save(context, display)
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        return LeapmotorClusterRoute.identity(display)
    }

    private fun host(block: (CarPlayHostActivity) -> Unit) {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try { block(activity) } finally {
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(activity, name).shutdownNow()
            }
        }
    }

    @Test fun visibleWindowDeterminesEncodedCanvasAndKeepsMarkerInsideIt() {
        val key = select()
        assertTrue(LeapmotorClusterRoute.saveViewport(context, key, right))
        // Old BYD marker settings must not put the marker outside this independent visible window.
        AirPlayPersistence.saveClusterMarkerXPercent(context, 5)
        AirPlayPersistence.saveClusterMarkerYPercent(context, 95)
        host {
            val config = ReflectionHelpers.callInstanceMethod<AirPlayDisplayConfig>(it, "clusterDisplayConfig")
            assertEquals(880, config.widthPixels)
            assertEquals(624, config.heightPixels)
            assertEquals(100, AirPlayPersistence.loadClusterMapScalePercent(context))
            val safe = config.safeArea!!
            assertEquals(safe.left, safe.right)
            assertEquals(safe.top, safe.bottom)
            assertTrue(safe.left + safe.right < config.widthPixels)
        }
    }

    @Test fun calibratedRegionSurvivesReconfirmationButNotAnotherDisplay() {
        val key = select()
        assertTrue(LeapmotorClusterRoute.saveViewport(context, key, right))
        LeapmotorClusterRoute.saveMirrorMain(context, true)
        val framing = LeapmotorClusterRoute.Framing(true, 63, 72, 150)
        assertTrue(LeapmotorClusterRoute.saveFraming(context, key, framing))
        LeapmotorClusterRoute.save(context, LeapmotorClusterRoute.findDisplay(context))
        assertEquals(right, LeapmotorClusterRoute.viewport(context))
        assertEquals(framing, LeapmotorClusterRoute.configuration(context)!!.framing)
        assertTrue(LeapmotorClusterRoute.configuration(context)!!.mirrorMain)
        val another = select()
        assertNotEquals(key.id, another.id)
        assertEquals(SafeAreaRect(1200, 0, 1920, 720), LeapmotorClusterRoute.viewport(context))
        assertFalse(LeapmotorClusterRoute.configuration(context)!!.mirrorMain)
        assertEquals(LeapmotorClusterRoute.Framing(), LeapmotorClusterRoute.configuration(context)!!.framing)
        assertFalse(LeapmotorClusterRoute.saveViewport(context, key, right))
        assertFalse(LeapmotorClusterRoute.saveFraming(context, key, framing))
    }

    @Test fun removedDisplayCannotAcceptCalibrationOrMirrorFallback() {
        val key = select()
        LeapmotorClusterRoute.saveMirrorMain(context, true)
        ShadowDisplayManager.removeDisplay(key.id)
        assertFalse(LeapmotorClusterRoute.saveViewport(context, key, right))
        host {
            assertNull(ReflectionHelpers.callInstanceMethod<AirPlayDisplayConfig?>(it, "clusterDisplayConfig"))
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(it, "clusterMirrorsMain"))
        }
    }

    @Test fun measuredPanelStartsWithSquareCanvasAndOtherPanelsKeepTheirFullArea() {
        select()
        assertEquals(SafeAreaRect(1200, 0, 1920, 720), LeapmotorClusterRoute.viewport(context))
        assertEquals(SafeAreaRect(0, 0, 1760, 720), LeapmotorClusterViewport.defaultRect(1760, 720))
        host {
            val config = ReflectionHelpers.callInstanceMethod<AirPlayDisplayConfig>(it, "clusterDisplayConfig")
            assertEquals(720, config.widthPixels)
            assertEquals(720, config.heightPixels)
        }
    }

    @Test fun explicitlySavedWholeCanvasStillOverridesMeasuredDefault() {
        val key = select()
        val full = SafeAreaRect(0, 0, 1920, 720)
        assertTrue(LeapmotorClusterRoute.saveViewport(context, key, full))
        assertEquals(full, LeapmotorClusterRoute.viewport(context))
    }

    @Test fun invalidSelectedGeometryDoesNotFallBackToAnUnrelatedVirtualScreen() {
        select()
        context.getSharedPreferences("leapmotor_cluster_route", 0).edit().putInt("width", 0).apply()
        assertNull(LeapmotorClusterRoute.configuration(context))
        host {
            assertNull(ReflectionHelpers.callInstanceMethod<AirPlayDisplayConfig?>(it, "clusterDisplayConfig"))
        }
    }

    @Test fun mirrorUsesMainStreamWithoutReplacingItsSurfaceOrAdvertisingAltDisplay() {
        val key = select()
        LeapmotorClusterRoute.saveViewport(context, key, right)
        LeapmotorClusterRoute.saveMirrorMain(context, true)
        val mainTexture = SurfaceTexture(0)
        val clusterTexture = SurfaceTexture(1)
        val main = Surface(mainTexture)
        val cluster = Surface(clusterTexture)
        try { host { activity ->
            val alt = ReflectionHelpers.callInstanceMethod<AirPlayDisplayConfig?>(activity, "clusterDisplayConfig")
            assertNull(alt)
            assertEquals(110, ReflectionHelpers.callInstanceMethod<Int>(activity, "clusterOutputType"))
            val primary = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 840)
            val info = AirPlayInfoPlist.build(AirPlayConfig(deviceName = "test", deviceId = "test",
                btMac = "test", sourceVersion = "366.0", main = primary, cluster = alt))
            val displays = info["displays"] as List<*>
            assertEquals(1, displays.size)
            assertEquals(110, (displays.single() as Map<*, *>)["type"])
            assertEquals(1920, (displays.single() as Map<*, *>)["widthPixels"])
            val sink = mock(AndroidMediaSink::class.java)
            ReflectionHelpers.setField(activity, "sink", sink)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onClusterSurface",
                ReflectionHelpers.ClassParameter.from(Surface::class.java, cluster))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "attachSurface",
                ReflectionHelpers.ClassParameter.from(Surface::class.java, main))
            verify(sink).setSurface(110, main)
            verify(sink, atLeastOnce()).setMirrorSurface(110, "leapmotor-cluster-main", cluster)
            verify(sink, never()).setSurface(111, cluster)
            verify(sink, never()).setSurface(110, cluster)
            // Pending settings do not reroute a running session before reconnection.
            LeapmotorClusterRoute.saveMirrorMain(context, false)
            LeapmotorClusterRoute.saveViewport(context, key, SafeAreaRect(0, 0, 1920, 720))
            LeapmotorClusterRoute.saveFraming(context, key, LeapmotorClusterRoute.Framing(true, 0, 0, 300))
            assertEquals(110, ReflectionHelpers.callInstanceMethod<Int>(activity, "clusterOutputType"))
            val snapshot = ReflectionHelpers.getField<LeapmotorClusterRoute.Configuration>(activity, "clusterStreamTarget")
            assertEquals(right, snapshot.viewport)
            assertTrue(snapshot.mirrorMain)
            assertEquals(LeapmotorClusterRoute.Framing(), snapshot.framing)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "dismissClusterPresentation")
            verify(sink).setMirrorSurface(110, "leapmotor-cluster-main", null)
            verify(sink, never()).clearSurface(110, main)
        } } finally { main.release(); cluster.release(); mainTexture.release(); clusterTexture.release() }
    }

    @Test fun contentFitsTheVisibleRectangleAtDifferentWindowSizes() {
        val child = View(context)
        val frame = LeapmotorClusterViewportFrame(context, 1920, 720, right, child)
        fun layout(w: Int, h: Int) {
            frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, w, h)
        }
        layout(1920, 720)
        assertEquals(right, SafeAreaRect(child.left, child.top, child.right, child.bottom))
        layout(960, 360)
        assertEquals(SafeAreaRect(510, 12, 950, 324), SafeAreaRect(child.left, child.top, child.right, child.bottom))
        frame.mirrorAspect = 1920.0 / 840
        layout(1920, 720)
        assertEquals(880, child.width)
        assertEquals(385, child.height)
        assertTrue(child.left >= right.left && child.right <= right.right)
        assertTrue(child.top >= right.top && child.bottom <= right.bottom)
    }

    @Test fun localCropMovesBothAxesAndClampsAtSourceEdges() {
        val bottomRight = LeapmotorClusterRoute.Framing(true, 100, 100, 200)
        assertEquals(android.graphics.RectF(1560f, 360f, 1920f, 720f),
            LeapmotorClusterViewport.sourceCrop(1920, 720, 720, 720, bottomRight))
        assertEquals(android.graphics.RectF(0f, 0f, 360f, 360f),
            LeapmotorClusterViewport.sourceCrop(1920, 720, 720, 720, bottomRight.copy(centerX = 0, centerY = 0)))
        assertEquals(android.graphics.RectF(0f, 280f, 720f, 1000f),
            LeapmotorClusterViewport.sourceCrop(720, 1280, 720, 720, LeapmotorClusterRoute.Framing(true)))
        assertEquals(android.graphics.RectF(0f, 0f, 1920f, 720f),
            LeapmotorClusterViewport.sourceCrop(1920, 720, 720, 720, LeapmotorClusterRoute.Framing()))
    }

    @Test fun textureTransformMapsTheChosenSourceRectangleOntoTheVisibleWindow() {
        val framing = LeapmotorClusterRoute.Framing(true, 80, 25, 200)
        val crop = LeapmotorClusterViewport.sourceCrop(1920, 1080, 720, 720, framing)
        val points = floatArrayOf(crop.left * 720 / 1920, crop.top * 720 / 1080,
            crop.right * 720 / 1920, crop.bottom * 720 / 1080)
        LeapmotorClusterViewport.textureTransform(1920, 1080, 720, 720, framing).mapPoints(points)
        assertArrayEquals(floatArrayOf(0f, 0f, 720f, 720f), points, 0.01f)
        assertTrue(LeapmotorClusterViewport.textureTransform(1920, 1080, 720, 720,
            LeapmotorClusterRoute.Framing()).isIdentity)
    }

    @Test fun leapmotorPresentationUsesLocalTextureAndFitsActualVideoGeometry() {
        val key = select()
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(key.id)
        val presentation = ClusterMapPresentation(activity.get(), display) {}
        try {
            presentation.show()
            assertTrue(ReflectionHelpers.getField<View>(presentation, "videoView") is LeapmotorClusterVideoTexture)
            presentation.setVideoSourceSize(1920, 1080)
            val frame = ReflectionHelpers.getField<LeapmotorClusterViewportFrame>(presentation, "viewportFrame")
            frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1920, 720)
            val root = frame.getChildAt(0)
            assertEquals(720, root.width)
            assertEquals(405, root.height)
            assertEquals(1200, root.left)
            assertTrue(root.top >= 0 && root.bottom <= 720)
        } finally {
            presentation.dismiss()
            activity.pause().stop().destroy()
        }
    }
}
