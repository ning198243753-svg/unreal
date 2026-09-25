package com.moon.location.xposed

import android.os.SystemClock
import android.util.Log
import com.moon.location.config.ConfigSnapshot
import com.moon.location.config.Keys
import io.github.libxposed.api.XposedModule

/**
 * Reads and caches the cross-process config snapshot from LSPosed remote preferences.
 *
 * Refreshes are cheap and throttled to [MIN_INTERVAL_MS]; the parsed snapshot is
 * reused in between so the location hot path never blocks on preferences I/O.
 */
class SpoofState(private val module: XposedModule) {
    private companion object {
        const val TAG = "MoonLocation.SpoofState"
        const val MIN_INTERVAL_MS = 200L
    }

    @Volatile private var cached: ConfigSnapshot? = null
    @Volatile private var lastRead = 0L
    private val lock = Any()

    private val prefs by lazy { module.getRemotePreferences(Keys.GROUP) }

    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRead < MIN_INTERVAL_MS) return
        synchronized(lock) {
            if (SystemClock.elapsedRealtime() - lastRead < MIN_INTERVAL_MS) return
            lastRead = now
            val raw = try {
                prefs.getString(Keys.KEY_SNAPSHOT, null)
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "read snapshot failed: ${t.message}")
                null
            }
            val parsed = ConfigSnapshot.fromJson(raw)
            if (parsed != null && (cached == null || parsed.revision >= cached!!.revision)) {
                cached = parsed
            }
        }
    }

    /** Snapshot if spoofing is currently active (started + lease fresh). */
    fun active(): ConfigSnapshot? {
        refresh()
        val snap = cached ?: return null
        return if (snap.isActive(System.currentTimeMillis(), SystemClock.elapsedRealtime())) snap else null
    }

    fun current(): ConfigSnapshot? {
        refresh()
        return cached
    }
}
