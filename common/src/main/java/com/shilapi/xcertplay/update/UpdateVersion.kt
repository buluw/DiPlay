package com.shilapi.xcertplay.update

internal object UpdateVersion {
    private val VERSION = Regex("^[vV]?([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)(?:-[A-Za-z0-9.-]+)?$")

    internal fun parse(value: String): List<Int>? {
        val match = VERSION.matchEntire(value) ?: return null
        return match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }.let {
            it + List(4 - it.size) { 0 }
        }
    }

    internal fun isNewer(remote: String, installed: String): Boolean {
        val target = parse(remote) ?: return false
        // Release 0.2.19.1 republishes versionCode 38 / 0.2.19-leapmotor unchanged.
        val installedRelease = if (installed == "0.2.19-leapmotor") "0.2.19.1" else installed
        val current = parse(installedRelease) ?: return true
        for (index in target.indices) if (target[index] != current[index]) return target[index] > current[index]
        return false
    }
}
