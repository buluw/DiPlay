package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoVisibleSizeTest {
    @Test fun codedPaddingIsExcludedUsingInclusiveBounds() {
        assertEquals(1920 to 1080, videoVisibleSize(1920, 1088, 0, 0, 1919, 1079))
        assertEquals(600 to 600, videoVisibleSize(608, 608, 4, 4, 603, 603))
    }

    @Test fun missingOrMalformedCropCannotProduceInvalidGeometry() {
        assertEquals(720 to 720, videoVisibleSize(720, 720, null, null, null, null))
        assertEquals(720 to 720, videoVisibleSize(720, 720, 0, 0, 720, 719))
        assertEquals(720 to 720, videoVisibleSize(720, 720, -1, 0, 719, 719))
        assertNull(videoVisibleSize(0, 720, null, null, null, null))
    }

    @Test fun clusterDimensionsDoNotChangeTheMainOrUnconfiguredStreams() {
        val sink = AndroidMediaSink(videoWidth = 1920, videoHeight = 840)
        try {
            sink.setVideoSize(111, 720, 720)
            assertEquals(720 to 720, sink.videoSize(111))
            assertEquals(1920 to 840, sink.videoSize(110))
            assertEquals(1920 to 840, sink.videoSize(112))
            val create = AndroidMediaSink::class.java.declaredMethods.single { it.name == "newVideoDecoder" }
                .apply { isAccessible = true }
            for ((stream, expected) in listOf(110 to (1920 to 840), 111 to (720 to 720))) {
                // Inspect the real decoder construction without starting a worker or a native codec.
                val decoder = create.invoke(sink, stream, null, null, false)
                try {
                    val width = decoder.javaClass.getDeclaredField("width").apply { isAccessible = true }.getInt(decoder)
                    val height = decoder.javaClass.getDeclaredField("height").apply { isAccessible = true }.getInt(decoder)
                    assertEquals(expected, width to height)
                } finally { (decoder as java.io.Closeable).close() }
            }
        } finally { sink.close() }
    }
}
