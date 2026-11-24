package com.smartscan.scan

/**
 * Temporal filter for live quad detections (normalized corners).
 *
 * - A detection close to the tracked quad refines it with an exponential moving average.
 * - A different quad replaces the tracked one only after it shows up in [confirmFrames] consecutive frames.
 * - Missing detections keep the last quad for [holdFrames] frames, so the overlay does not flicker.
 */
class QuadTracker(
    private val matchTol: Float = 0.05f,
    private val confirmFrames: Int = 3,
    private val holdFrames: Int = 5,
    private val alpha: Float = 0.35f,
) {
    private var current: FloatArray? = null
    private var candidate: FloatArray? = null
    private var candidateCount = 0
    private var misses = 0

    @Synchronized
    fun reset() {
        current = null
        candidate = null
        candidateCount = 0
        misses = 0
    }

    @Synchronized
    fun update(q: FloatArray?): FloatArray? {
        val cur = current
        if (q != null && cur != null && Corners.maxDelta(cur, q) < matchTol) {
            misses = 0
            candidate = null
            candidateCount = 0
            return Corners.lerp(cur, q, alpha).also { current = it }
        }
        if (q == null) {
            candidate = null
            candidateCount = 0
        } else {
            val cand = candidate
            if (cand != null && Corners.maxDelta(cand, q) < matchTol) {
                candidateCount++
                candidate = Corners.lerp(cand, q, 0.5f)
            } else {
                candidate = q
                candidateCount = 1
            }
            val needed = if (cur == null) 2 else confirmFrames
            if (candidateCount >= needed) {
                current = candidate
                candidate = null
                candidateCount = 0
                misses = 0
                return current
            }
        }
        if (cur != null && ++misses > holdFrames) current = null
        return current
    }
}
