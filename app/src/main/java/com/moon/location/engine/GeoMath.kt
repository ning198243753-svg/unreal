package com.moon.location.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geodesic helpers used for random jitter and route work. */
object GeoMath {
    private const val RADIUS_EARTH_M = 6_371_000.0

    /** Move a point by [northM] / [eastM] metres and return the new (lat, lng). */
    fun offset(lat: Double, lng: Double, northM: Double, eastM: Double): Pair<Double, Double> {
        val dLat = northM / RADIUS_EARTH_M
        val dLng = eastM / (RADIUS_EARTH_M * cos(Math.toRadians(lat)))
        return (lat + Math.toDegrees(dLat)) to (lng + Math.toDegrees(dLng))
    }

    /** Great-circle distance in metres. */
    fun distance(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * RADIUS_EARTH_M * asin(sqrt(h))
    }
}
