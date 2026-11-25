package com.smartscan.scan

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log

/**
 * Keeps the latest device position while the scanner is open and «GPS» is on, so a capture gets a
 * location instantly. Uses the platform LocationManager (no Play Services). Main thread only.
 */
class LocationTracker(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile var latest: Location? = null
        private set
    private var running = false

    private val listener = LocationListener { loc ->
        val cur = latest
        if (cur == null || loc.time >= cur.time || loc.accuracy < cur.accuracy) latest = loc
    }

    /** Caller must hold a location permission. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        latest = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, 5_000L, 5f, listener, Looper.getMainLooper()) }
                .onFailure { Log.w("Location", "updates $p", it) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { lm.removeUpdates(listener) }
    }

    /** Latest fix if not older than 10 minutes. */
    fun current(): Location? = latest?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }

    val anyProviderEnabled: Boolean
        get() = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
}
