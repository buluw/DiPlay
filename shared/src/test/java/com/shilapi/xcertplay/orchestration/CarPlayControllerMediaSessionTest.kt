package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.RtspMessage
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise the real controller and AirPlay stream teardown, without starting a phone transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerMediaSessionTest {
    private val airPlayConfig = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
        sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
    private lateinit var controller: CarPlayController
    private lateinit var sessionListener: AirPlaySessionListener
    private val sessions = mutableListOf<AirPlaySession>()

    @Before fun setUp() {
        controller = CarPlayController(
            context = RuntimeEnvironment.getApplication(),
            config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                    firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
                ),
            ),
            airPlayConfig = airPlayConfig, identity = identity, pairings = PairingStore(),
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
            reportStatus = {},
        )
        sessionListener = controller.javaClass.getDeclaredField("sessionListener")
            .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
    }

    @After fun tearDown() {
        sessions.forEach { it.close() }
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
    }

    @Test fun waitingControllerHasNoMediaSessionAndRejectsMediaKeys() {
        assertFalse(controller.hasActiveMediaSession())
        assertFalse(controller.sendMediaButton(CarPlayMediaButton.NEXT))
    }

    @Test fun primaryScreenTeardownRetainsAudioAndMediaControlSession() {
        val session = activeSession()
        assertNotNull(controller.activeAirPlaySessionToken())
        assertTrue(controller.hasActiveMediaSession())

        teardownPrimaryScreen(session)

        assertNull(controller.activeAirPlaySessionToken())
        assertFalse(session.isClosed)
        assertEquals(setOf(MAIN_AUDIO), session.activeStreams)
        assertTrue(controller.hasActiveMediaSession())
        assertTrue(controller.sendMediaButton(CarPlayMediaButton.NEXT))
    }

    @Test fun endingMediaSessionAfterPrimaryScreenTeardownRejectsMediaKeys() {
        val session = activeSession()
        teardownPrimaryScreen(session)
        assertTrue(controller.hasActiveMediaSession())

        session.close()

        assertFalse(controller.hasActiveMediaSession())
        assertFalse(controller.sendMediaButton(CarPlayMediaButton.NEXT))
    }

    @Test fun closedControllerRejectsMediaKeysEvenWithARetainedSession() {
        val session = activeSession()
        teardownPrimaryScreen(session)
        controller.close()

        assertFalse(session.isClosed)
        assertFalse(controller.hasActiveMediaSession())
        assertFalse(controller.sendMediaButton(CarPlayMediaButton.NEXT))
    }

    private fun activeSession(): AirPlaySession {
        val session = AirPlaySession(
            socket = Socket(), config = airPlayConfig, identity = identity,
            pairings = PairingStore(), mfi = null, listener = sessionListener,
            media = object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 1
                override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?> =
                    mapOf("type" to type, "dataPort" to 1, "controlPort" to 2)
            },
        ).also { sessions += it }
        val streams = listOf(
            mapOf("type" to MAIN_SCREEN.toLong()),
            mapOf("type" to MAIN_AUDIO.toLong()),
        )
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java)
            .apply { isAccessible = true }.invoke(session, streams)
        sessionListener.onSessionActive(session)
        return session
    }

    private fun teardownPrimaryScreen(session: AirPlaySession) {
        val request = RtspMessage.Request(
            "TEARDOWN", "*", "RTSP/1.0", emptyMap(),
            BplistCodec.encode(mapOf("streams" to listOf(
                mapOf("type" to MAIN_SCREEN.toLong()),
            ))),
        )
        session.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session, request)
    }

    private companion object {
        const val MAIN_SCREEN = 110
        const val MAIN_AUDIO = 100
    }
}
