package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LeapmotorMediaInfoTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController
    private val track = CarPlayNowPlaying(title = "歌曲 \"一\"", artist = "歌手", playing = true)

    @Before fun start() {
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        controller = newController()
        LeapmotorWheelKeys.setEnabled(app, false)
        LeapmotorWheelKeys.setDiagnosticsEnabled(app, true)
        CarPlayMediaKeys.attach(app, controller)
    }

    @After fun stop() {
        CarPlayMediaKeys.detach(controller)
        LeapmotorWheelKeys.setEnabled(app, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun optInPublishesRetainedMetadataAndOnlyMeaningfulPlaybackChanges() {
        update(track)
        assertTrue(reports().isEmpty())
        LeapmotorWheelKeys.setEnabled(app, true)
        val sent = reports().single()
        assertEquals(0x11000000, sent.flags)
        val payload = JSONObject(sent.getStringExtra("info")!!)
        assertEquals("music", payload.getString("type"))
        val data = payload.getJSONObject("data")
        assertEquals(14, data.getInt("type"))
        assertEquals(track.title, data.getString("title"))
        assertEquals(track.title, data.getString("name"))
        assertEquals(track.artist, data.getString("artist"))
        assertEquals(1, data.getInt("state"))
        update(track.copy(elapsedMillis = 1000))
        update(track.copy(elapsedMillis = 2000))
        assertEquals(1, reports().size)
        update(track.copy(playing = false))
        update(track)
        update(track.copy(title = "下一首"))
        assertEquals(listOf(1, 0, 1, 1), reports().map { data(it).getInt("state") })
    }

    @Test fun disabledAndDisconnectedSessionsRetractPlayingOnceAndNeverKeepAdvertising() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        LeapmotorWheelKeys.setEnabled(app, false)
        update(track.copy(title = "关闭期间"))
        assertEquals(listOf(1, 0), reports().map { data(it).getInt("state") })
        LeapmotorWheelKeys.setEnabled(app, true)
        assertEquals("关闭期间", data(reports().last()).getString("title"))
        `when`(controller.hasActiveMediaSession()).thenReturn(false)
        update(track)
        update(track.copy(elapsedMillis = 9000))
        CarPlayMediaKeys.detach(controller)
        assertEquals(listOf(1, 0, 1, 0), reports().map { data(it).getInt("state") })
    }

    @Test fun firstPausedTrackIsReportedAsPausedAndNoScreenStreamIsRequired() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(null)
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track.copy(playing = false))
        assertEquals(0, data(reports().single()).getInt("state"))
        assertTrue(LeapmotorWheelKeys.snapshot().connected)
    }

    @Test fun clearingMetadataRetractsThePreviousPlayingState() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        update(CarPlayNowPlaying())
        assertEquals(listOf(1, 0), reports().map { data(it).getInt("state") })
    }

    @Test fun oldControllerUpdatesAndQueuedAudioDoNotAdvertiseForANewConnection() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        val old = controller
        LeapmotorWheelKeys.updateMedia(old, track, true)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        controller = newController()
        CarPlayMediaKeys.attach(app, controller)
        assertEquals(LeapmotorWheelKeys.MediaReportState.WAITING, LeapmotorWheelKeys.snapshot().mediaReport)
        LeapmotorWheelKeys.updateMedia(old, track, true)
        LeapmotorWheelKeys.detach(old)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1, 0), reports().map { data(it).getInt("state") })
        update(track.copy(title = "新连接"))
        assertEquals("新连接", data(reports().last()).getString("title"))
    }

    @Test fun broadcastFailureIsVisibleAndTheSameStateCanBeRetried() {
        var fail = true
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) {
                if (fail && intent.action == LeapmotorWheelKeys.MEDIA_INFO_ACTION) throw SecurityException("denied")
                super.sendBroadcast(intent)
            }
        }
        LeapmotorWheelKeys.initialize(context)
        LeapmotorWheelKeys.setEnabled(context, true)
        update(track)
        assertTrue(reports().isEmpty())
        assertEquals(LeapmotorWheelKeys.MediaReportState.FAILED, LeapmotorWheelKeys.snapshot().mediaReport)
        assertEquals("SecurityException", LeapmotorWheelKeys.snapshot().mediaReportError)
        fail = false
        update(track.copy(elapsedMillis = 3000))
        assertEquals(1, reports().size)
        assertEquals(LeapmotorWheelKeys.MediaReportState.SENT, LeapmotorWheelKeys.snapshot().mediaReport)
    }

    @Test fun stoppedDiagnosticsRetainSuccessfulReportsAcrossReconnectAndReplacement() {
        assertStoppedDiagnosticsSurviveReconnect(failLastReport = false)
    }

    @Test fun stoppedDiagnosticsRetainFailedReportsAcrossReconnectAndReplacement() {
        assertStoppedDiagnosticsSurviveReconnect(failLastReport = true)
    }

    private fun assertStoppedDiagnosticsSurviveReconnect(failLastReport: Boolean) {
        var fail = false
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) {
                if (fail && intent.action == LeapmotorWheelKeys.MEDIA_INFO_ACTION) throw SecurityException("denied")
                super.sendBroadcast(intent)
            }
        }
        LeapmotorWheelKeys.initialize(context)
        LeapmotorWheelKeys.setEnabled(context, true)
        update(track)
        if (failLastReport) {
            fail = true
            update(track.copy(title = "Failed report"))
        }
        LeapmotorWheelKeys.setDiagnosticsEnabled(context, false)
        val captured = LeapmotorWheelKeys.snapshot()
        val report = LeapmotorWheelKeys.diagnosticReport()
        assertEquals(if (failLastReport) LeapmotorWheelKeys.MediaReportState.FAILED
            else LeapmotorWheelKeys.MediaReportState.SENT, captured.mediaReport)
        assertEquals(1L, captured.mediaReports)
        assertEquals(true, captured.reportedPlaying)
        assertNotNull(captured.mediaReportTimeMillis)
        assertEquals(if (failLastReport) "SecurityException" else null, captured.mediaReportError)
        fail = false
        for (disconnectFirst in listOf(true, false)) {
            val previousReports = reports().size
            if (disconnectFirst) CarPlayMediaKeys.detach(controller)
            controller = newController()
            `when`(controller.nowPlayingSnapshot()).thenReturn(track.copy(title = "Reconnected track"))
            CarPlayMediaKeys.attach(app, controller)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(captured, LeapmotorWheelKeys.snapshot())
            assertEquals(report, LeapmotorWheelKeys.diagnosticReport())
            // Runtime source retraction and the new connection still work while recording is off.
            assertEquals(listOf(0, 1), reports().drop(previousReports).map { data(it).getInt("state") })
        }
    }

    @Test fun permanentFocusLossCachesBackgroundTracksWithoutReclaimingTheMediaSource() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        val focus = startMediaAudio()
        // Robolectric's MediaController shadow does not receive real MediaSession setter calls.
        // Observe those calls directly, so this verifies Android metadata publication as well.
        val session = spy(ReflectionHelpers.getField<MediaSession>(CarPlayMediaKeys, "session"))
        ReflectionHelpers.setField(CarPlayMediaKeys, "session", session)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        update(track.copy(title = "后台下一首", artist = "另一位歌手", elapsedMillis = 1200))
        LeapmotorWheelKeys.setEnabled(app, false)
        LeapmotorWheelKeys.setEnabled(app, true)
        assertEquals(listOf(1, 0), reports().map { data(it).getInt("state") })
        val metadata = ArgumentCaptor.forClass(MediaMetadata::class.java)
        val playback = ArgumentCaptor.forClass(PlaybackState::class.java)
        verify(session).setMetadata(metadata.capture())
        verify(session).setPlaybackState(playback.capture())
        assertEquals("后台下一首", metadata.value.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals(PlaybackState.STATE_PLAYING, playback.value.state)

        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(1, 0, 1), reports().map { data(it).getInt("state") })
        assertEquals("后台下一首", data(reports().last()).getString("title"))
        assertEquals("另一位歌手", data(reports().last()).getString("artist"))
    }

    @Test fun explicitResumeReclaimsOnceOnlyAfterItsFocusRequestIsGranted() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        startMediaAudio().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        update(track.copy(playing = false))
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        iphoneResumes()
        update(track)
        assertEquals(listOf(1, 0), reports().map { data(it).getInt("state") })
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        iphoneResumes()
        iphoneResumes()
        assertEquals(listOf(1, 0, 1), reports().map { data(it).getInt("state") })
    }

    @Test fun transientFocusLossKeepsTheCurrentSourceAndDoesNotDuplicateTheGainReport() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        val focus = startMediaAudio()
        for (change in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            focus.onAudioFocusChange(change)
            update(track.copy(title = "语音打断期间 $change"))
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        assertEquals(listOf(1, 1, 1), reports().map { data(it).getInt("state") })
    }

    @Test fun newerPermanentLossInvalidatesAQueuedGrantBeforeItCanReclaimTheSource() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        val focus = startMediaAudio()
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        queueRegainFocus()
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        update(track.copy(title = "仍在后台"))
        assertEquals(listOf(1, 0), reports().map { data(it).getInt("state") })
    }

    @Test fun replacementAndDetachRejectStaleFocusOwnershipChanges() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        val old = controller
        val oldFocus = startMediaAudio()
        oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        queueRegainFocus()
        controller = newController()
        CarPlayMediaKeys.attach(app, controller)
        update(track.copy(title = "新连接"))
        val currentFocus = startMediaAudio()
        currentFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        LeapmotorWheelKeys.setMediaSourceReleased(old, false)
        update(track.copy(title = "新连接在后台"))
        assertEquals(listOf(1, 0, 1, 0), reports().map { data(it).getInt("state") })
        currentFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        update(track.copy(title = "新连接下一首"))
        assertEquals(listOf(1, 0, 1, 0, 1, 1), reports().map { data(it).getInt("state") })
        CarPlayMediaKeys.detach(controller)
        val count = reports().size
        currentFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        LeapmotorWheelKeys.setMediaSourceReleased(controller, false)
        assertEquals(count, reports().size)
    }

    @Test fun oldRequestForTheSameControllerCannotReclaimAReattachedSource() {
        LeapmotorWheelKeys.setEnabled(app, true)
        update(track)
        val oldFocus = startMediaAudio()
        CarPlayMediaKeys.detach(controller)
        CarPlayMediaKeys.attach(app, controller)
        update(track)
        val currentFocus = startMediaAudio()
        currentFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        update(track.copy(title = "重新连接后仍在后台"))
        assertEquals(listOf(1, 0, 1, 0), reports().map { data(it).getInt("state") })
        currentFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(1, 0, 1, 0, 1), reports().map { data(it).getInt("state") })
    }

    private fun startMediaAudio(): AudioManager.OnAudioFocusChangeListener {
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        val request = ReflectionHelpers.getField<AudioFocusRequestCompat>(CarPlayMediaKeys, "focusRequest")
        return ReflectionHelpers.getField(request, "listener")
    }

    private fun iphoneResumes() {
        CarPlayMediaKeys::class.java.getDeclaredMethod("onIphonePlaying", CarPlayController::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(CarPlayMediaKeys, controller, true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun queueRegainFocus() {
        CarPlayMediaKeys::class.java.getDeclaredMethod("regainFocusLocked").apply { isAccessible = true }
            .let { method -> synchronized(CarPlayMediaKeys) { method.invoke(CarPlayMediaKeys) } }
    }

    private fun update(info: CarPlayNowPlaying) {
        // Exercise the real CarPlayMediaKeys metadata publication path without a network session.
        `when`(controller.nowPlayingSnapshot()).thenReturn(info)
        CarPlayMediaKeys::class.java.getDeclaredMethod("onNowPlayingChanged", CarPlayController::class.java)
            .apply { isAccessible = true }.invoke(CarPlayMediaKeys, controller)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun attachingReplaysRetainedMetadataToLeapmotorWithoutAnotherPhoneUpdate() {
        CarPlayMediaKeys.detach(controller)
        LeapmotorWheelKeys.setEnabled(app, true)
        `when`(controller.nowPlayingSnapshot()).thenReturn(track)
        CarPlayMediaKeys.attach(app, controller)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(track.title, data(reports().single()).getString("title"))
        assertEquals(1, data(reports().single()).getInt("state"))
    }

    private fun newController() = mock(CarPlayController::class.java).also {
        `when`(it.hasActiveMediaSession()).thenReturn(true)
        `when`(it.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
    }

    private fun reports() = shadowOf(app).broadcastIntents.filter { it.action == LeapmotorWheelKeys.MEDIA_INFO_ACTION }
    private fun data(intent: Intent) = JSONObject(intent.getStringExtra("info")!!).getJSONObject("data")
}
