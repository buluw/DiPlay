package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class LeapmotorWheelKeysTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController

    @Before fun start() {
        controller = mock(CarPlayController::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(controller.hasActiveMediaSession()).thenReturn(true)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        LeapmotorWheelKeys.setEnabled(context, false)
        LeapmotorWheelKeys.setDiagnosticsEnabled(context, true)
        LeapmotorWheelKeys.clearHistory()
        LeapmotorWheelKeys.attach(context, controller)
    }

    @After fun stop() {
        LeapmotorWheelKeys.setEnabled(context, false)
        LeapmotorWheelKeys.detach(controller)
        LeapmotorWheelKeys.clearHistory()
    }

    @Test fun explicitlyDisabledStopsReceivingImmediately() {
        broadcast("2")
        assertEquals(0L, LeapmotorWheelKeys.snapshot().received)
        verify(controller, never()).sendMediaButton(anyInt())

        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast("2")
        LeapmotorWheelKeys.setEnabled(context, false)
        broadcast("2")

        assertEquals(1L, LeapmotorWheelKeys.snapshot().received)
        assertEquals(LeapmotorWheelKeys.ListenerState.DISABLED, LeapmotorWheelKeys.snapshot().listener)
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun mapsNativeStringValuesForTheAdvertisedSource() {
        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast("1")
        broadcast("2")
        broadcast("3")

        verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
        assertEquals(3L, LeapmotorWheelKeys.snapshot().submitted)
        assertEquals(listOf(14, 14, 14), LeapmotorWheelKeys.snapshot().events.map { it.playMediaType })
    }

    @Test fun otherMediaSourcesRemainVisibleInDiagnosticsWithoutControllingTheIphone() {
        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast("1", mediaType = 6)
        broadcast("2", mediaType = 13)
        broadcast(Intent(LeapmotorWheelKeys.ACTION).putExtra("value", "3"))
        assertEquals(3L, LeapmotorWheelKeys.snapshot().received)
        assertTrue(LeapmotorWheelKeys.snapshot().events.all { it.result == LeapmotorWheelKeys.Result.IGNORED })
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun releaseUnknownAndWrongTypeValuesAreRecordedWithoutSendingCommands() {
        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast("0")
        broadcast("unknown")
        broadcast(Intent(LeapmotorWheelKeys.ACTION).putExtra("value", 2))
        broadcast(Intent(LeapmotorWheelKeys.ACTION))
        broadcast(Intent("unrelated.action").putExtra("value", "2"))

        val state = LeapmotorWheelKeys.snapshot()
        assertEquals(4L, state.received)
        assertTrue(state.events.all { it.result == LeapmotorWheelKeys.Result.IGNORED })
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun registeredListenerCanReportKeysWhileConnectionHasNotCompleted() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(null)
        `when`(controller.hasActiveMediaSession()).thenReturn(false)
        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast("2")

        assertFalse(LeapmotorWheelKeys.snapshot().connected)
        assertEquals(LeapmotorWheelKeys.Result.NOT_CONNECTED, LeapmotorWheelKeys.snapshot().events.last().result)
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun mediaKeysAndDiagnosticsRemainConnectedWithoutAPrimaryScreenStream() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(null)
        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast("1")
        broadcast("2")
        broadcast("3")

        val state = LeapmotorWheelKeys.snapshot()
        assertTrue(state.connected)
        assertEquals(3L, state.submitted)
        assertTrue(state.events.all { it.result == LeapmotorWheelKeys.Result.SUBMITTED })
        verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
        assertTrue(LeapmotorWheelKeys.diagnosticReport().contains("carPlayConnected=true"))
    }

    @Test fun endedMediaSessionStopsForwardingAndUpdatesDiagnostics() {
        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast("2")
        `when`(controller.activeAirPlaySessionToken()).thenReturn(null)
        `when`(controller.hasActiveMediaSession()).thenReturn(false)
        broadcast("2")

        val state = LeapmotorWheelKeys.snapshot()
        assertFalse(state.connected)
        assertEquals(2L, state.received)
        assertEquals(1L, state.submitted)
        assertEquals(LeapmotorWheelKeys.Result.NOT_CONNECTED, state.events.last().result)
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        assertTrue(LeapmotorWheelKeys.diagnosticReport().contains("carPlayConnected=false"))
    }

    @Test fun failedSubmissionIsNotReportedAsSuccessfulIphoneExecution() {
        `when`(controller.sendMediaButton(anyInt())).thenReturn(false)
        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast("2")

        assertEquals(0L, LeapmotorWheelKeys.snapshot().submitted)
        assertEquals(LeapmotorWheelKeys.Result.FAILED, LeapmotorWheelKeys.snapshot().events.last().result)
    }

    @Test fun oldControllerDetachingDoesNotRemoveReplacementReceiver() {
        val old = controller
        controller = mock(CarPlayController::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(controller.hasActiveMediaSession()).thenReturn(true)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        LeapmotorWheelKeys.setEnabled(context, true)
        LeapmotorWheelKeys.attach(context, controller)
        LeapmotorWheelKeys.detach(old)
        broadcast("2")

        assertEquals(LeapmotorWheelKeys.ListenerState.REGISTERED, LeapmotorWheelKeys.snapshot().listener)
        verify(old, never()).sendMediaButton(anyInt())
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)

        LeapmotorWheelKeys.detach(controller)
        broadcast("2")
        assertEquals(1L, LeapmotorWheelKeys.snapshot().received)
        assertEquals(LeapmotorWheelKeys.ListenerState.WAITING, LeapmotorWheelKeys.snapshot().listener)
    }

    @Test fun boundedHistoryRetainsCountsAndClearKeepsListenerRunning() {
        LeapmotorWheelKeys.setEnabled(context, true)
        repeat(25) { broadcast("0") }
        assertEquals(25L, LeapmotorWheelKeys.snapshot().received)
        assertEquals(20, LeapmotorWheelKeys.snapshot().events.size)

        LeapmotorWheelKeys.clearHistory()
        assertEquals(0L, LeapmotorWheelKeys.snapshot().received)
        assertEquals(LeapmotorWheelKeys.ListenerState.REGISTERED, LeapmotorWheelKeys.snapshot().listener)
        broadcast("2")
        assertEquals(1L, LeapmotorWheelKeys.snapshot().received)
        assertEquals(1L, LeapmotorWheelKeys.snapshot().submitted)
    }

    @Test fun reportContainsUsefulFieldsAndExcludesOtherBroadcastPayloads() {
        LeapmotorWheelKeys.setEnabled(context, true)
        broadcast(Intent(LeapmotorWheelKeys.ACTION).putExtra("value", "2")
            .putExtra("playMediaType", 14).putExtra("unrelated", "private-payload"))

        val report = LeapmotorWheelKeys.diagnosticReport()
        assertTrue(report.contains("value=2 playMediaType=14"))
        assertTrue(report.contains("result=SUBMITTED"))
        assertTrue(report.contains("not confirmed by iPhone"))
        assertFalse(report.contains("private-payload"))
    }

    @Test fun freshInstallEnablesControlsButDoesNotRecordKeysOrWriteWheelLogs() {
        context.getSharedPreferences("leapmotor_wheel_test", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        LeapmotorWheelKeys.clearHistory()
        LeapmotorWheelKeys.initialize(context)
        ShadowLog.clear()

        broadcast("2")

        assertTrue(LeapmotorWheelKeys.enabled(context))
        assertFalse(LeapmotorWheelKeys.diagnosticsEnabled(context))
        assertEquals(LeapmotorWheelKeys.ListenerState.REGISTERED, LeapmotorWheelKeys.snapshot().listener)
        assertEquals(0L, LeapmotorWheelKeys.snapshot().received)
        assertTrue(LeapmotorWheelKeys.snapshot().events.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("DiPlay-LeapmotorKeys").isEmpty())
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun recordingSwitchDoesNotStopKeysOrMediaAndStartsANewCaptureWhenEnabled() {
        LeapmotorWheelKeys.setEnabled(context, true)
        val track = com.shilapi.xcertplay.media.CarPlayNowPlaying(title = "First", playing = true)
        LeapmotorWheelKeys.updateMedia(controller, track, true)
        broadcast("1")
        val recorded = LeapmotorWheelKeys.snapshot()
        LeapmotorWheelKeys.setDiagnosticsEnabled(context, false)
        LeapmotorWheelKeys.initialize(context)
        ShadowLog.clear()

        broadcast("2")
        LeapmotorWheelKeys.updateMedia(controller, track.copy(title = "Second"), true)
        assertEquals(recorded.copy(diagnosticsEnabled = false), LeapmotorWheelKeys.snapshot())
        assertTrue(ShadowLog.getLogsForTag("DiPlay-LeapmotorKeys").isEmpty())
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        val reports = shadowOf(context).broadcastIntents.filter { it.action == LeapmotorWheelKeys.MEDIA_INFO_ACTION }
        assertEquals(2, reports.size)
        assertTrue(reports.last().getStringExtra("info")!!.contains("Second"))

        LeapmotorWheelKeys.setDiagnosticsEnabled(context, true)
        assertEquals(0L, LeapmotorWheelKeys.snapshot().received)
        assertEquals(0L, LeapmotorWheelKeys.snapshot().mediaReports)
        assertTrue(LeapmotorWheelKeys.snapshot().events.isEmpty())
        broadcast("3")
        LeapmotorWheelKeys.updateMedia(controller, track.copy(playing = false), false)
        assertEquals(1L, LeapmotorWheelKeys.snapshot().submitted)
        assertEquals(1L, LeapmotorWheelKeys.snapshot().mediaReports)
        assertTrue(LeapmotorWheelKeys.diagnosticReport().contains("diagnosticsEnabled=true"))
    }

    private fun broadcast(value: String, mediaType: Int = 14) = broadcast(
        Intent(LeapmotorWheelKeys.ACTION).putExtra("value", value).putExtra("playMediaType", mediaType),
    )

    private fun broadcast(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
