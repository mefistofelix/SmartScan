package com.smartscan.scan

/**
 * Decides when to take the picture and when the next page is ready.
 *
 * SEARCHING -> (quad found) STEADYING -> (quad still + sharp for [stableMs]) CAPTURING
 * -> WAIT_NEXT -> (scene changed or page removed) SEARCHING ...
 *
 * Stillness is measured against an anchor (the quad when the hold started), not frame to frame,
 * so a slow drift or a framing still being adjusted restarts the countdown. The hold is long
 * enough for a person to stop moving and check the framing before the shot.
 */
class AutoCaptureController(
    private val stableMs: Long = 1600,
    private val moveTolerance: Float = 0.025f,
    private val minSharpness: Double = 25.0,
    private val minArea: Float = 0.15f,
) {
    enum class Phase { SEARCHING, STEADYING, CAPTURING, WAIT_NEXT }

    data class Decision(val phase: Phase, val progress: Float, val capture: Boolean)

    @Volatile var phase = Phase.SEARCHING
        private set

    private var anchor: FloatArray? = null
    private var stableSince = 0L
    private var capturedSig: ByteArray? = null
    private var waitSince = 0L
    private var lostSince = 0L

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
                    phase = Phase.SEARCHING
                    anchor = null
                    return Decision(phase, 0f, false)
                }
                val a = anchor
                if (a == null || Corners.maxDelta(a, quad) > moveTolerance || sharpness < minSharpness) {
                    anchor = quad
                    phase = Phase.STEADYING
                    stableSince = now
                    return Decision(phase, 0f, false)
                }
                val p = ((now - stableSince).toFloat() / stableMs).coerceIn(0f, 1f)
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
