package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** Explicit public-display route. No guessed display 1/2, OEM mode setters or ADB routing. */
internal object LeapmotorClusterRoute {
    private const val PREFS = "leapmotor_cluster_route"
    data class Identity(val id: Int, val name: String, val width: Int, val height: Int, val flags: Int) {
        fun label(): String = id.toString() + ": " + name + " · " + width + " × " + height
        fun accepts(other: Identity): Boolean = this == other && id > Display.DEFAULT_DISPLAY &&
            width > 0 && height > 0 && flags and Display.FLAG_PRIVATE == 0
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
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        if (display != null) {
            val key = identity(display)
            require(display.isValid && key.accepts(key))
            edit.putInt("id", key.id).putString("name", key.name).putInt("width", key.width)
                .putInt("height", key.height).putInt("flags", key.flags)
        }
        edit.apply()
    }

    /** Fail closed on removal, renumbering or changed panel properties; require another selection. */
    fun findDisplay(context: Context): Display? {
        val key = selected(context) ?: return null
        return candidates(context).singleOrNull { key.accepts(identity(it)) }
    }

    fun diagnosticReport(context: Context): String =
        "leapmotorCluster=" + selected(context)?.label() +
            " available=" + (findDisplay(context) != null) + " route=public-presentation"
}
