package com.shilapi.xcertplay.media

/** MediaCodec crop bounds are inclusive and may exclude padded rows in the coded buffer. */
internal fun videoVisibleSize(width: Int, height: Int, left: Int?, top: Int?, right: Int?, bottom: Int?): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    if (left == null || top == null || right == null || bottom == null ||
        left < 0 || top < 0 || right < left || bottom < top || right >= width || bottom >= height) return width to height
    return (right - left + 1) to (bottom - top + 1)
}
