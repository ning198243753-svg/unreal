package com.moon.location.config

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * App-side writer for the cross-process config channel.
 *
 * Writes go into the LSPosed remote-preferences group [Keys.GROUP]. The module
 * running inside system_server reads the same group and applies the snapshot.
 *
 * Kept intentionally tiny: one JSON blob + a monotonic revision counter.
 */
object Config {
    private const val TAG = "MoonLocation.Config"

    @Volatile
    private var revision: Long = System.currentTimeMillis()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(Keys.GROUP, Context.MODE_PRIVATE)

    /** Persist a new snapshot. Returns the new revision, or -1 on failure. */
    @JvmStatic
    fun write(
        ctx: Context,
        started: Boolean,
        lat: Double,
        lng: Double,
        alt: Double = 0.0,
        accuracy: Float = 5f,
        speed: Float = 0f,
        bearing: Float = 0f,
        provider: String = "gps",
    ): Long {
        val now = System.currentTimeMillis()
        val next = maxOf(revision + 1, now)
        revision = next
        val snap = ConfigSnapshot(
            revision = next,
            wall = now,
            elapsed = SystemClock.elapsedRealtime(),
            started = started,
            lat = lat,
            lng = lng,
            alt = alt,
            accuracy = accuracy,
            speed = speed,
            bearing = bearing,
            provider = provider,
        )
        val ok = prefs(ctx).edit()
            .putString(Keys.KEY_SNAPSHOT, snap.toJson())
            .putInt(Keys.KEY_MODULE_VERSION, 1)
            .commit()
        if (!ok) {
            Log.w(TAG, "failed to persist snapshot")
            return -1
        }
        return next
    }

    /** Read the current snapshot, or null. */
    @JvmStatic
    fun read(ctx: Context): ConfigSnapshot? =
        ConfigSnapshot.fromJson(prefs(ctx).getString(Keys.KEY_SNAPSHOT, null))

    /** Refresh only the lease timestamps of the current snapshot (heartbeat). */
    @JvmStatic
    fun heartbeat(ctx: Context) {
        val current = ConfigSnapshot.fromJson(prefs(ctx).getString(Keys.KEY_SNAPSHOT, null)) ?: return
        val now = System.currentTimeMillis()
        val next = maxOf(revision + 1, now)
        revision = next
        prefs(ctx).edit()
            .putString(
                Keys.KEY_SNAPSHOT,
                current.copy(
                    revision = next,
                    wall = now,
                    elapsed = SystemClock.elapsedRealtime(),
                ).toJson(),
            )
            .commit()
    }
}
