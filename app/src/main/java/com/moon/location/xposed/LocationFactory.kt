package com.moon.location.xposed

import android.location.Location
import android.util.Log
import com.moon.location.config.ConfigSnapshot

/**
 * Builds / mutates [Location] objects for the spoof. Runs inside system_server.
 *
 * Strategy for M1: mutate the existing `Location` instance in place. The framework
 * caches and hands out the very same objects, so in-place mutation also fixes the
 * last-known-location cache without touching `LocationResult` internals.
 */
object LocationFactory {
    private const val TAG = "MoonLocation.LocationFactory"

    /**
     * Overwrite [loc] with the spoofed fix from [snap].
     *
     * Returns true if the location was changed.
     */
    @JvmStatic
    fun applyTo(loc: Location, snap: ConfigSnapshot): Boolean {
        loc.latitude = snap.lat
        loc.longitude = snap.lng
        if (snap.alt != 0.0) loc.altitude = snap.alt
        if (snap.accuracy > 0f) loc.accuracy = snap.accuracy
        if (snap.speed > 0f) loc.speed = snap.speed
        if (snap.bearing > 0f) loc.bearing = snap.bearing
        // Keep the timestamp close to "now" so recency checks pass.
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        loc.elapsedRealtimeNanos = now
        loc.time = System.currentTimeMillis()
        clearMockFlag(loc)
        return true
    }

    /**
     * Best-effort removal of the "mock location" marker. In system_server the
     * platform is exempt from hidden-API restrictions, so plain reflection works.
     */
    private fun clearMockFlag(loc: Location) {
        runCatching {
            Location::class.java.getMethod("setIsFromMockProvider", Boolean::class.javaPrimitiveType)
                .invoke(loc, false)
        }
        runCatching {
            Location::class.java.getMethod("setMock", Boolean::class.javaPrimitiveType)
                .invoke(loc, false)
        }
    }

    /** Create a fresh spoofed [Location] with the given provider name. */
    @JvmStatic
    fun create(snap: ConfigSnapshot): Location {
        val loc = Location(snap.provider)
        loc.latitude = snap.lat
        loc.longitude = snap.lng
        loc.accuracy = if (snap.accuracy > 0f) snap.accuracy else 5f
        if (snap.alt != 0.0) loc.altitude = snap.alt
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        clearMockFlag(loc)
        return loc
    }
}
