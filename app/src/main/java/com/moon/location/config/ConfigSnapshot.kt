package com.moon.location.config

import org.json.JSONObject

/**
 * Immutable, validated snapshot of the spoof state.
 *
 * The app serialises one of these into remote preferences; the system_server side
 * parses and validates it before applying. A snapshot carries a monotonic
 * [revision] and a lease ([wall] / [elapsed]) so a stale writer cannot keep
 * spoofing alive forever if the app crashed.
 */
data class ConfigSnapshot(
    val revision: Long,
    val wall: Long,
    val elapsed: Long,
    val started: Boolean,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val provider: String,
) {
    fun toJson(): String = JSONObject().apply {
        put(F_SCHEMA, SCHEMA)
        put(F_REVISION, revision)
        put(F_WALL, wall)
        put(F_ELAPSED, elapsed)
        put(F_STARTED, started)
        put(F_LAT, lat)
        put(F_LNG, lng)
        put(F_ALT, alt)
        put(F_ACCURACY, accuracy.toDouble())
        put(F_SPEED, speed.toDouble())
        put(F_BEARING, bearing.toDouble())
        put(F_PROVIDER, provider)
    }.toString()

    /** True when the writer has refreshed the snapshot within [leaseMs]. */
    fun isFresh(nowWall: Long, nowElapsed: Long, leaseMs: Long = LEASE_MS): Boolean {
        val dw = nowWall - wall
        val de = nowElapsed - elapsed
        return dw in -2_000..leaseMs && de in 0..leaseMs
    }

    fun isActive(nowWall: Long, nowElapsed: Long, leaseMs: Long = LEASE_MS): Boolean =
        started && isFresh(nowWall, nowElapsed, leaseMs)

    companion object {
        const val SCHEMA = 1
        const val LEASE_MS = 15_000L

        private const val F_SCHEMA = "_schema"
        private const val F_REVISION = "_revision"
        private const val F_WALL = "_wall"
        private const val F_ELAPSED = "_elapsed"
        private const val F_STARTED = "started"
        private const val F_LAT = "lat"
        private const val F_LNG = "lng"
        private const val F_ALT = "alt"
        private const val F_ACCURACY = "accuracy"
        private const val F_SPEED = "speed"
        private const val F_BEARING = "bearing"
        private const val F_PROVIDER = "provider"

        /** Parse + validate. Returns null on any malformed / out-of-range input. */
        fun fromJson(json: String?): ConfigSnapshot? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                if (o.optInt(F_SCHEMA, -1) != SCHEMA) return null
                val revision = o.getLong(F_REVISION)
                val lat = o.getDouble(F_LAT)
                val lng = o.getDouble(F_LNG)
                if (revision <= 0) return null
                if (!lat.isFinite() || !lng.isFinite()) return null
                if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return null
                if (!o.has(F_STARTED)) return null
                ConfigSnapshot(
                    revision = revision,
                    wall = o.getLong(F_WALL),
                    elapsed = o.getLong(F_ELAPSED),
                    started = o.getBoolean(F_STARTED),
                    lat = lat,
                    lng = lng,
                    alt = o.optDouble(F_ALT, 0.0),
                    accuracy = o.optDouble(F_ACCURACY, 0.0).toFloat(),
                    speed = o.optDouble(F_SPEED, 0.0).toFloat(),
                    bearing = o.optDouble(F_BEARING, 0.0).toFloat(),
                    provider = o.optString(F_PROVIDER, "gps").ifBlank { "gps" },
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
