package com.smartscan.scan

/**
 * Decides when to take the picture and when the next page is ready.
 *
 * SEARCHING -> (quad found) STEADYING -> (quad held + sharp for [stableMs]) CAPTURING
 * -> WAIT_NEXT -> (scene changed or page removed) SEARCHING ...
 *
 * Stillness is measured against an anchor (the quad when the hold started), so a real reframing or a
 * slow drift restarts the countdown, while [moveTolerance] is wide enough to absorb the tremor of a
 * hand-held phone. Blurry frames pause the countdown instead of resetting it, and a short detection
 * dropout (up to [dropoutMs]) is ignored.
 */
class AutoCaptureController(
    private val stableMs: Long = 1000,
    private val moveTolerance: Float = 0.05f,
    private val minSharpness: Double = 25.0,
    private val minArea: Float = 0.15f,
    private val dropoutMs: Long = 350,
) {
    enum class Phase { SEARCHING, STEADYING, CAPTURING, WAIT_NEXT }

    data class Decision(val phase: Phase, val progress: Float, val capture: Boolean)

    @Volatile var phase = Phase.SEARCHING
        private set

    private var anchor: FloatArray? = null
    private var held = 0L
    private var lastNow = 0L
    private var missingSince = 0L
    private var capturedSig: ByteArray? = null
    private var waitSince = 0L
    private var lostSince = 0L

    private fun progress() = (held.toFloat() / stableMs).coerceIn(0f, 1f)

    @Synchronized
    fun onFrame(quad: FloatArray?, sharpness: Double, sig: ByteArray, now: Long): Decision {
        when (phase) {
            Phase.CAPTURING -> return Decision(phase, 1f, false)
            Phase.WAIT_NEXT -> {
                if (quad == null) {
                    if (lostSince == 0L) lostSince = now
                } else lostSince = 0L
                val changed = capturedSig?.let { DocumentDetector.signatureDiff(it, sig) > 20.0 } ?: true
                val lostLong = lostSince != 0L && now - lostSince > 400
                if (now - waitSince > 1200 && (changed || lostLong)) {
                    phase = Phase.SEARCHING
                    anchor = null
                }
                return Decision(phase, 0f, false)
            }
            else -> {
                if (quad == null || Corners.area(quad) < minArea) {
                    if (anchor != null) {
                        if (missingSince == 0L) missingSince = now
                        if (now - missingSince <= dropoutMs) {
                            lastNow = now
                            return Decision(phase, progress(), false)
                        }
                    }
                    phase = Phase.SEARCHING
                    anchor = null
                    return Decision(phase, 0f, false)
                }
                missingSince = 0L
                val a = anchor
                if (a == null || Corners.maxDelta(a, quad) > moveTolerance) {
                    anchor = quad
                    held = 0L
                    lastNow = now
                    phase = Phase.STEADYING
                    return Decision(phase, 0f, false)
                }
                val dt = (now - lastNow).coerceIn(0L, 200L)
                lastNow = now
                if (sharpness >= minSharpness) held += dt
                val p = progress()
                if (p >= 1f) {
                    phase = Phase.CAPTURING
                    return Decision(phase, 1f, true)
                }
                phase = Phase.STEADYING
                return Decision(phase, p, false)
            }
        }
    }

    @Synchronized
    fun onCaptured(sig: ByteArray, now: Long) {
        capturedSig = sig
        waitSince = now
        lostSince = 0L
        phase = Phase.WAIT_NEXT
    }

    @Synchronized
    fun reset() {
        phase = Phase.SEARCHING
        anchor = null
        capturedSig = null
    }
}
