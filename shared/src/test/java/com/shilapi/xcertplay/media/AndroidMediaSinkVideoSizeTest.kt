package com.shilapi.xcertplay.media

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class AndroidMediaSinkVideoSizeTest {
    @Test fun backgroundMirrorReportsActualSizeWithoutAPrimaryDecoder() = Fixture().use { f ->
        val mirror = f.mirror(MAIN)
        assertTrue(f.primary.isEmpty())
        assertEquals(1920 to 840, f.sink.videoSize(MAIN))

        f.report(mirror, 1920, 1080)

        assertEquals(1920 to 1080, f.sink.videoSize(MAIN))
        assertEquals(listOf(Triple(MAIN, 1920, 1080)), f.events)
        assertEquals(720 to 720, f.sink.videoSize(ALT))
    }

    @Test fun primaryAndMirrorShareSizeChangesWithoutDuplicateEvents() = Fixture().use { f ->
        val primary = f.primary(MAIN)
        val mirror = f.mirror(MAIN)
        f.report(primary, 1920, 1080)
        f.report(mirror, 1920, 1080)
        f.report(mirror, 1280, 720)
        f.report(primary, 1280, 720)

        assertEquals(1280 to 720, f.sink.videoSize(MAIN))
        assertEquals(listOf(Triple(MAIN, 1920, 1080), Triple(MAIN, 1280, 720)), f.events)
    }

    @Test fun replacedOrRemovedMirrorCannotOverwriteTheCurrentSize() = Fixture().use { f ->
        val old = f.mirror(MAIN)
        val current = f.mirror(MAIN)
        f.report(current, 1920, 1080)
        // Keep the old fixture open: identity filtering must reject it even before close completes.
        f.report(old, 640, 480)
        assertEquals(1920 to 1080, f.sink.videoSize(MAIN))

        f.sink.setMirrorSurface(MAIN, MIRROR_KEY, null)
        assertFalse(f.mirrors.containsKey(MAIN to MIRROR_KEY))
        f.report(current, 1280, 720)
        assertEquals(1920 to 1080, f.sink.videoSize(MAIN))
        assertEquals(listOf(Triple(MAIN, 1920, 1080)), f.events)
    }

    @Test fun closedDecodersCannotReportWhileStillRegisteredForCleanup() = Fixture().use { f ->
        val primary = f.primary(MAIN)
        val mirror = f.mirror(MAIN)
        f.report(mirror, 1920, 1080)
        primary.close()
        mirror.close()
        assertSame(primary, f.primary[MAIN])
        assertSame(mirror, f.mirrors[MAIN to MIRROR_KEY])

        f.report(primary, 640, 480)
        f.report(mirror, 1280, 720)

        assertEquals(1920 to 1080, f.sink.videoSize(MAIN))
        assertEquals(listOf(Triple(MAIN, 1920, 1080)), f.events)
    }

    @Test fun exitingMirrorIsRemovedAndItsLateExitCannotRemoveAReplacement() = Fixture().use { f ->
        val primary = f.primary(MAIN)
        val old = f.mirror(MAIN)
        val otherStream = f.mirror(ALT)
        f.exit(old)
        assertFalse(f.mirrors.containsKey(MAIN to MIRROR_KEY))
        assertSame(primary, f.primary[MAIN])
        assertSame(otherStream, f.mirrors[ALT to MIRROR_KEY])
        f.report(old, 640, 480)
        assertTrue(f.events.isEmpty())

        val replaced = f.mirror(MAIN)
        val current = f.mirror(MAIN)
        f.exit(replaced)
        assertSame(current, f.mirrors[MAIN to MIRROR_KEY])
        f.report(current, 1920, 1080)
        f.report(replaced, 640, 480)
        assertEquals(listOf(Triple(MAIN, 1920, 1080)), f.events)
    }

    @Test fun unregisteredAndWrongStreamMirrorsCannotReportSizes() = Fixture().use { f ->
        val unregistered = f.decoder(MAIN)
        f.report(unregistered, 640, 480)
        // The callback captures MAIN; registration for ALT does not make it a current MAIN mirror.
        f.mirrors[ALT to MIRROR_KEY] = unregistered
        f.report(unregistered, 1280, 720)

        assertTrue(f.events.isEmpty())
        assertEquals(1920 to 840, f.sink.videoSize(MAIN))
        assertEquals(720 to 720, f.sink.videoSize(ALT))
    }

    @Test fun streamTeardownRejectsOldReportsAndAcceptsTheRebuiltBackgroundMirror() = Fixture().use { f ->
        val primary = f.primary(MAIN)
        val old = f.mirror(MAIN)
        val otherStream = f.mirror(ALT)
        f.sink.onScreenStreamActive(MAIN, true)
        f.report(old, 1920, 1080)
        f.report(otherStream, 600, 600)
        f.sink.onScreenStreamActive(MAIN, false)
        assertEquals(1920 to 840, f.sink.videoSize(MAIN))
        assertFalse(f.primary.containsKey(MAIN))
        assertFalse(f.mirrors.containsKey(MAIN to MIRROR_KEY))
        assertSame(otherStream, f.mirrors[ALT to MIRROR_KEY])

        f.events.clear()
        f.report(primary, 640, 480)
        f.report(old, 1920, 1080)
        assertTrue(f.events.isEmpty())
        f.sink.onScreenStreamActive(MAIN, true)
        val current = f.mirror(MAIN)
        f.report(current, 1280, 720)
        f.exit(old)
        f.report(old, 1920, 1080)

        assertTrue(f.primary.isEmpty())
        assertSame(current, f.mirrors[MAIN to MIRROR_KEY])
        assertEquals(1280 to 720, f.sink.videoSize(MAIN))
        assertEquals(600 to 600, f.sink.videoSize(ALT))
        assertEquals(listOf(Triple(MAIN, 1280, 720)), f.events)
    }

    @Test fun closedSinkCannotRestoreSizesFromLatePrimaryOrMirrorReports() = Fixture().use { f ->
        val primary = f.primary(MAIN)
        val mirror = f.mirror(MAIN)
        f.report(mirror, 1920, 1080)
        f.sink.close()
        f.events.clear()
        // Observe erroneous notifications even though close cleared the previous listener.
        f.sink.setVideoSizeChangedListener { type, width, height -> f.events += Triple(type, width, height) }
        f.report(primary, 640, 480)
        f.report(mirror, 1280, 720)

        assertEquals(1920 to 840, f.sink.videoSize(MAIN))
        assertTrue(f.events.isEmpty())
    }

    /** Use real decoder callbacks and sink registries without starting a worker or native codec. */
    private class Fixture : Closeable {
        val sink = AndroidMediaSink(videoWidth = 1920, videoHeight = 840)
        val events = mutableListOf<Triple<Int, Int, Int>>()
        val primary: MutableMap<Int, Any> = field(sink, "videoDecoders")
        val mirrors: MutableMap<Pair<Int, String>, Any> = field(sink, "mirrorDecoders")
        private val decoders = mutableListOf<Closeable>()
        private val create = AndroidMediaSink::class.java.declaredMethods.single { it.name == "newVideoDecoder" }
            .apply { isAccessible = true }

        init {
            sink.setVideoSize(ALT, 720, 720)
            sink.setVideoSizeChangedListener { type, width, height -> events += Triple(type, width, height) }
        }

        fun decoder(type: Int): Closeable = (create.invoke(sink, type, null, null, false) as Closeable)
            .also { decoders += it }

        fun primary(type: Int): Closeable = decoder(type).also { primary[type] = it }

        fun mirror(type: Int): Closeable = decoder(type).also { mirrors[type to MIRROR_KEY] = it }

        fun report(decoder: Closeable, width: Int, height: Int) {
            field<(Any, Int, Int) -> Unit>(decoder, "onOutputSize")(decoder, width, height)
        }

        fun exit(decoder: Closeable) {
            decoder.close()
            field<(Any) -> Unit>(decoder, "onExit")(decoder)
        }

        override fun close() {
            sink.close()
            decoders.forEach(Closeable::close)
        }
    }

    companion object {
        private const val MAIN = 110
        private const val ALT = 111
        private const val MIRROR_KEY = "leapmotor-cluster-main"

        @Suppress("UNCHECKED_CAST")
        private fun <T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
