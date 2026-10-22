package fr.anon.app

import kotlin.math.max
import kotlin.math.min

/** Coordinates are normalized in the upright image, with the origin at its top left. */
data class FaceRegion(val id: Int, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun padded(amount: Float = .20f) = copy(
        left = (left - width * amount).coerceAtLeast(0f),
        top = (top - height * amount).coerceAtLeast(0f),
        right = (right + width * amount).coerceAtMost(1f),
        bottom = (bottom + height * amount).coerceAtMost(1f)
    )
    fun union(other: FaceRegion) = copy(
        left = min(left, other.left), top = min(top, other.top),
        right = max(right, other.right), bottom = max(bottom, other.bottom)
    )
}
