package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect

/** Explicit public-display route. No guessed display 1/2, OEM mode setters or ADB routing. */
internal object LeapmotorClusterRoute {
    private const val PREFS = "leapmotor_cluster_route"
    data class Identity(val id: Int, val name: String, val width: Int, val height: Int, val flags: Int) {
        fun label(): String = id.toString() + ": " + name + " · " + width + " × " + height
        fun accepts(other: Identity): Boolean = this == other && id > Display.DEFAULT_DISPLAY &&
            width > 0 && height > 0 && flags and Display.FLAG_PRIVATE == 0
    }

    data class Framing(val crop: Boolean = false, val centerX: Int = 50, val centerY: Int = 50, val zoom: Int = 100) {
        fun normalized() = copy(centerX = centerX.coerceIn(0, 100), centerY = centerY.coerceIn(0, 100),
            zoom = zoom.coerceIn(100, 300))
    }

    data class Configuration(val identity: Identity, val viewport: SafeAreaRect, val mirrorMain: Boolean,
        val framing: Framing = Framing())

    fun configuration(context: Context): Configuration? {
        val key = selected(context) ?: return null
        val rect = viewport(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Configuration(key, rect, prefs.getBoolean("mirror_main", false),
            Framing(prefs.getBoolean("local_crop", false), prefs.getInt("crop_x", 50),
                prefs.getInt("crop_y", 50), prefs.getInt("crop_zoom", 100)).normalized())
    }

    fun saveFraming(context: Context, expected: Identity, framing: Framing): Boolean {
        if (selected(context) != expected || findDisplay(context) == null) return false
        val value = framing.normalized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("local_crop", value.crop).putInt("crop_x", value.centerX)
            .putInt("crop_y", value.centerY).putInt("crop_zoom", value.zoom).apply()
        return true
    }

    fun saveMirrorMain(context: Context, mirror: Boolean) {
        if (!configured(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("mirror_main", mirror).apply()
    }

    fun identity(display: Display): Identity {
        val size = ClusterMapPresentation.sizeOf(display)
        return Identity(display.displayId, display.name, size.x, size.y, display.flags)
    }

    @Suppress("DEPRECATION")
    fun candidates(context: Context): List<Display> {
        val mainDisplay = (context as? android.app.Activity)?.windowManager?.defaultDisplay?.displayId
            ?: Display.DEFAULT_DISPLAY
        return context.getSystemService(DisplayManager::class.java)?.displays.orEmpty().filter {
            it.displayId != mainDisplay && it.isValid && identity(it).let { key -> key.accepts(key) }
        }
    }

    fun selected(context: Context): Identity? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("id")) return null
        return Identity(prefs.getInt("id", 0), prefs.getString("name", "").orEmpty(),
            prefs.getInt("width", 0), prefs.getInt("height", 0), prefs.getInt("flags", 0))
    }

    fun configured(context: Context): Boolean = selected(context) != null

    fun save(context: Context, display: Display?) {
        // Reconfirming the same panel must not discard its calibrated visible area.
        if (display != null && display.isValid && selected(context) == identity(display)) return
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        if (display != null) {
            val key = identity(display)
            require(display.isValid && key.accepts(key))
            edit.putInt("id", key.id).putString("name", key.name).putInt("width", key.width)
                .putInt("height", key.height).putInt("flags", key.flags)
        }
        edit.apply()
    }

    /** The OEM may expose a whole HDMI canvas while showing only its right-hand map window. */
    fun viewport(context: Context): SafeAreaRect? {
        val key = selected(context) ?: return null
        if (!key.accepts(key)) return null
        val saved = SafeAreaCodec.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("viewport", null))
        return saved?.clampTo(key.width, key.height)
            ?: LeapmotorClusterViewport.defaultRect(key.width, key.height)
    }

    fun saveViewport(context: Context, expected: Identity, rect: SafeAreaRect): Boolean {
        if (selected(context) != expected || findDisplay(context) == null) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("viewport", SafeAreaCodec.encode(rect.clampTo(expected.width, expected.height))).apply()
        return true
    }

    /** Fail closed on removal, renumbering or changed panel properties; require another selection. */
    fun findDisplay(context: Context): Display? {
        val key = selected(context) ?: return null
        return candidates(context).singleOrNull { key.accepts(identity(it)) }
    }

    fun diagnosticReport(context: Context): String =
        "leapmotorCluster=" + selected(context)?.label() +
            " available=" + (findDisplay(context) != null) + " viewport=" + viewport(context) +
            " mirrorMain=" + configuration(context)?.mirrorMain +
            " localFraming=" + configuration(context)?.framing + " route=public-presentation"
}
