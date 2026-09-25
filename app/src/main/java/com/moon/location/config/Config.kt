package com.moon.location.config

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * App-side writer for the cross-process config channel.
 *
 * The module in system_server reads the snapshot from two places, first non-null wins:
 *  1. LSPosed remote preferences (group [Keys.GROUP]).
 *  2. The root-written shared file [Keys.SHARED_SNAPSHOT_PATH]
 *     (world-readable, shell_data_file) — the reliable channel on this ROM.
 *
 * So every write publishes to BOTH channels.
 */
object Config {
    private const val TAG = "MoonLocation.Config"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "moon-config-writer").apply { isDaemon = true }
    }

    @Volatile
    private var revision: Long = System.currentTimeMillis()

    /**
     * Open the config prefs.
     *
     * LSPosed's remote-preferences bridge only exposes prefs that were opened
     * world-readable, so we request MODE_WORLD_READABLE. On modern Android that
     * throws unless LSPosed hooks the call (which it does when this module's app
     * is itself in the module's LSPosed scope), hence the private fallback.
     */
    @Suppress("DEPRECATION")
    private fun prefs(ctx: Context) = try {
        ctx.applicationContext.getSharedPreferences(Keys.GROUP, Context.MODE_WORLD_READABLE)
    } catch (_: Throwable) {
        ctx.applicationContext.getSharedPreferences(Keys.GROUP, Context.MODE_PRIVATE)
    }

    /** Persist a new snapshot to both channels. Returns the new revision, or -1 on failure. */
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
        val json = snap.toJson()
        val ok = prefs(ctx).edit()
            .putString(Keys.KEY_SNAPSHOT, json)
            .putInt(Keys.KEY_MODULE_VERSION, 1)
            .commit()
        makePrefsReadable(ctx)
        publishShared(ctx, json)
        if (!ok) {
            Log.w(TAG, "failed to persist snapshot to prefs")
            return -1
        }
        return next
    }

    /** Refresh only the lease timestamps of the current snapshot (heartbeat). */
    @JvmStatic
    fun heartbeat(ctx: Context) {
        val current = ConfigSnapshot.fromJson(prefs(ctx).getString(Keys.KEY_SNAPSHOT, null)) ?: return
        val now = System.currentTimeMillis()
        val next = maxOf(revision + 1, now)
        revision = next
        val json = current.copy(
            revision = next,
            wall = now,
            elapsed = SystemClock.elapsedRealtime(),
        ).toJson()
        prefs(ctx).edit().putString(Keys.KEY_SNAPSHOT, json).commit()
        makePrefsReadable(ctx)
        publishShared(ctx, json)
    }

    /**
     * Make the app's own prefs XML world-readable so system_server (system uid)
     * can read it. An app may chmod its own files; SELinux app_data_file is
     * readable by system_server on many ROMs, but if it is denied we fall back
     * to the root-written shared file.
     */
    private fun makePrefsReadable(ctx: Context) {
        runCatching {
            val dir = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs")
            java.io.File(dir, "${Keys.GROUP}.xml").setReadable(true, false)
        }
    }

    /** Read the current snapshot (app side), or null. */
    @JvmStatic
    fun read(ctx: Context): ConfigSnapshot? =
        ConfigSnapshot.fromJson(prefs(ctx).getString(Keys.KEY_SNAPSHOT, null))

    /** Mirror the JSON to the root-written, world-readable shared file. */
    private fun publishShared(ctx: Context, json: String) {
        io.execute {
            val ok = RootShell.writeSharedFile(Keys.SHARED_SNAPSHOT_PATH, json)
            if (!ok) Log.w(TAG, "shared file publish failed")
        }
    }
}
