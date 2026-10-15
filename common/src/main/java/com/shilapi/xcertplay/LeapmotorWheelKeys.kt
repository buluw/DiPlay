package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Media-state publisher and wheel receiver using Leapmotor's native Carlink protocol. */
internal object LeapmotorWheelKeys {
    const val ACTION = "com.leapmotor.action.METER.CTRL"
    const val MEDIA_INFO_ACTION = "com.leapmotor.transfer.MEDIA.INFO"
    const val MEDIA_TYPE = 14
    // Keep the preference file name so installed versions retain the user's choices.
    private const val PREFS = "leapmotor_wheel_test"
    private const val TAG = "DiPlay-LeapmotorKeys"
    private const val HISTORY_LIMIT = 20

    enum class ListenerState { DISABLED, WAITING, REGISTERED, ERROR }
    enum class Result { SUBMITTED, NOT_CONNECTED, IGNORED, VIDEO_PLAYER, FAILED }
    enum class MediaReportState { WAITING, SENT, FAILED }

    data class Event(
        val timeMillis: Long,
        val value: String?,
        val playMediaType: Int?,
        val button: Int?,
        val result: Result,
    )

    data class Snapshot(
        val enabled: Boolean = false,
        val diagnosticsEnabled: Boolean = false,
        val listener: ListenerState = ListenerState.DISABLED,
        val connected: Boolean = false,
        val received: Long = 0,
        val submitted: Long = 0,
        val events: List<Event> = emptyList(),
        val mediaReport: MediaReportState = MediaReportState.WAITING,
        val mediaReports: Long = 0,
        val mediaReportTimeMillis: Long? = null,
        val reportedPlaying: Boolean? = null,
        val mediaReportError: String? = null,
    )

    // Position updates are deliberately excluded: the iPhone sends them several times a second.
    private data class MediaInfo(val title: String, val artist: String, val playing: Boolean)

    private var app: Context? = null
    private var controller: CarPlayController? = null
    private var registered = false
    private var state = Snapshot()
    private var mediaInfo: MediaInfo? = null
    private var publishedMediaInfo: MediaInfo? = null
    private var mediaSourceReleased = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = receive(intent)
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun diagnosticsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("diagnostics_enabled", false)

    @Synchronized
    fun setDiagnosticsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("diagnostics_enabled", enabled).apply()
        if (enabled && !state.diagnosticsEnabled) clearHistory()
        state = state.copy(diagnosticsEnabled = enabled)
        WheelKeyLog.setRecording(enabled)
        if (enabled && WheelKeyService.connected()) WheelKeyLog.bound()
    }

    @Synchronized
    fun initialize(context: Context) {
        app = context.applicationContext
        state = state.copy(enabled = enabled(context), diagnosticsEnabled = diagnosticsEnabled(context))
        WheelKeyLog.setRecording(state.diagnosticsEnabled)
        updateRegistration()
        publishMediaInfo()
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled).apply()
        initialize(context)
    }

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            stopMediaInfo()
            mediaInfo = null
            mediaSourceReleased = false
            // Reconnecting must not change diagnostics retained after recording was stopped.
            if (state.diagnosticsEnabled) {
                state = state.copy(mediaReport = MediaReportState.WAITING, reportedPlaying = null,
                    mediaReportError = null)
            }
        }
        controller = next
        initialize(context)
    }

    /** An old controller ending must not remove the listener belonging to its replacement. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        stopMediaInfo()
        mediaInfo = null
        controller = null
        updateRegistration()
    }

    @Synchronized
    fun snapshot(): Snapshot = state.copy(
        connected = controller?.hasActiveMediaSession() == true,
    )

    @Synchronized
    fun clearHistory() {
        WheelKeyLog.reset()
        state = state.copy(received = 0, submitted = 0, events = emptyList(),
            mediaReport = MediaReportState.WAITING, mediaReports = 0,
            mediaReportTimeMillis = null, reportedPlaying = null, mediaReportError = null)
    }

    /** Called with the same retained iPhone state used by the Android media session. */
    @Synchronized
    fun updateMedia(expected: CarPlayController, update: CarPlayNowPlaying, playing: Boolean) {
        if (controller !== expected) return
        mediaInfo = if (playing || update.title != null || update.artist != null || update.sourceApp != null) {
            MediaInfo(update.title?.take(512) ?: "CarPlay", update.artist?.take(512).orEmpty(), playing)
        } else null
        publishMediaInfo()
    }

    /** A permanent focus loss revokes source claims, while metadata remains available on return. */
    @Synchronized
    fun setMediaSourceReleased(expected: CarPlayController, released: Boolean) {
        if (controller !== expected) return
        mediaSourceReleased = released
        publishMediaInfo()
    }

    private fun publishMediaInfo() {
        if (!state.enabled || !registered || mediaSourceReleased || controller?.hasActiveMediaSession() != true) {
            stopMediaInfo()
            return
        }
        val next = mediaInfo ?: run { stopMediaInfo(); return }
        if (next == publishedMediaInfo) return
        if (sendMediaInfo(next)) publishedMediaInfo = next
    }

    private fun stopMediaInfo() {
        // Only retract a state this instance actually published. Never invent a playing state,
        // write carlink_connect_state, or switch the Android audio stream to the OEM source ID.
        publishedMediaInfo?.takeIf { it.playing }?.let { sendMediaInfo(it.copy(playing = false)) }
        publishedMediaInfo = null
    }

    private fun sendMediaInfo(info: MediaInfo): Boolean {
        val context = app ?: return false
        return try {
            val payload = JSONObject().put("type", "music").put("data", JSONObject()
                .put("type", MEDIA_TYPE).put("name", info.title).put("title", info.title)
                .put("artist", info.artist).put("state", if (info.playing) 1 else 0))
            // Match the native Carlink and the successful in-car broadcast (include background receivers).
            context.sendBroadcast(Intent(MEDIA_INFO_ACTION).addFlags(0x11000000)
                .putExtra("info", payload.toString()))
            if (state.diagnosticsEnabled) {
                state = state.copy(mediaReport = MediaReportState.SENT, mediaReports = state.mediaReports + 1,
                    mediaReportTimeMillis = System.currentTimeMillis(), reportedPlaying = info.playing,
                    mediaReportError = null)
                Log.i(TAG, "media info sent type=$MEDIA_TYPE playing=${info.playing}")
            }
            true
        } catch (error: RuntimeException) {
            if (state.diagnosticsEnabled) {
                state = state.copy(mediaReport = MediaReportState.FAILED, mediaReportError = error.javaClass.simpleName)
                Log.w(TAG, "media info broadcast failed", error)
            }
            false
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun updateRegistration() {
        val context = app ?: return
        if (!state.enabled || controller == null) {
            if (registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
            state = state.copy(listener = if (state.enabled) ListenerState.WAITING else ListenerState.DISABLED)
            return
        }
        if (registered) return
        try {
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registered = true
            state = state.copy(listener = ListenerState.REGISTERED)
        } catch (error: RuntimeException) {
            state = state.copy(listener = ListenerState.ERROR)
            if (state.diagnosticsEnabled) Log.w(TAG, "wheel broadcast registration failed", error)
        }
    }

    @Synchronized
    @Suppress("DEPRECATION")
    private fun receive(intent: Intent) {
        if (intent.action != ACTION || !state.enabled || !registered) return
        // Keep only the two known fields; the report never retains arbitrary broadcast payloads.
        val extras = runCatching { intent.extras }.getOrNull()
        val value = runCatching { extras?.get("value") as? String }.getOrNull()
        val mediaType = runCatching { extras?.get("playMediaType") as? Int }.getOrNull()
        val button = when (value) {
            "1" -> CarPlayMediaButton.PREVIOUS
            "2" -> CarPlayMediaButton.NEXT
            "3" -> CarPlayMediaButton.PLAY_PAUSE
            else -> null
        }
        val current = controller
        // Other OEM sources must not also control the iPhone. Keep them in diagnostic history.
        val result = when {
            button == null || mediaType != MEDIA_TYPE -> Result.IGNORED
            current == null || !current.hasActiveMediaSession() -> Result.NOT_CONNECTED
            CarPlayVideo.onMediaKey(button) -> Result.VIDEO_PLAYER
            runCatching { current.sendMediaButton(button) }.getOrDefault(false) -> Result.SUBMITTED
            else -> Result.FAILED
        }
        // Recording is independent of forwarding. Do not allocate history or write logs when off.
        if (!state.diagnosticsEnabled) return
        val event = Event(System.currentTimeMillis(), value?.take(32), mediaType, button, result)
        state = state.copy(
            received = state.received + 1,
            submitted = state.submitted + if (result == Result.SUBMITTED) 1 else 0,
            events = (state.events + event).takeLast(HISTORY_LIMIT),
        )
        Log.i(TAG, "value=${event.value} playMediaType=$mediaType button=$button result=$result")
    }

    fun diagnosticReport(): String {
        val snapshot = snapshot()
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        return buildString {
            appendLine("--- Leapmotor steering-wheel diagnostics ---")
            appendLine("enabled=${snapshot.enabled} listener=${snapshot.listener} carPlayConnected=${snapshot.connected}")
            appendLine("diagnosticsEnabled=${snapshot.diagnosticsEnabled}")
            appendLine("received=${snapshot.received} submitted=${snapshot.submitted} retained=${snapshot.events.size}")
            appendLine("action=$ACTION; media-source filter=$MEDIA_TYPE; submitted means queued, not confirmed by iPhone")
            appendLine("mediaInfo action=$MEDIA_INFO_ACTION type=$MEDIA_TYPE status=${snapshot.mediaReport} " +
                "sent=${snapshot.mediaReports} playing=${snapshot.reportedPlaying} " +
                "lastSent=${snapshot.mediaReportTimeMillis} error=${snapshot.mediaReportError}; sent does not confirm OEM receipt")
            for (event in snapshot.events) {
                val value = event.value?.replace(Regex("[\\r\\n\\t]"), " ") ?: "<missing or not a string>"
                appendLine("${clock.format(Date(event.timeMillis))} value=$value playMediaType=${event.playMediaType} button=${event.button} result=${event.result}")
            }
        }
    }
}
