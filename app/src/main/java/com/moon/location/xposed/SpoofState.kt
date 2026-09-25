package com.moon.location.xposed

import android.os.SystemClock
import android.util.Log
import com.moon.location.config.ConfigSnapshot
import com.moon.location.config.Keys
import io.github.libxposed.api.XposedModule
import java.io.File
import java.io.FileInputStream

/**
 * Reads and caches the cross-process config snapshot.
 *
 * Channel priority (first non-null wins):
 *  1. LSPosed remote preferences (works on most ROMs).
 *  2. Direct read of the app's private prefs XML
 *     `/data/data/com.moon.location/shared_prefs/config.xml`.
 *     system_server runs as system/root, so it can read the file even though the
 *     app writes it MODE_PRIVATE. This does not depend on LSPosed's prefs bridge,
 *     which is empty on this ROM (verified: reads=112 readable=false).
 *
 * Refreshes are throttled to [MIN_INTERVAL_MS]; the parsed snapshot is reused in
 * between so the location hot path never blocks on I/O.
 */
class SpoofState(private val module: XposedModule) {
    private companion object {
        const val TAG = "MoonLocation.SpoofState"
        const val MIN_INTERVAL_MS = 200L
        const val PREFS_FILE = "/data/data/com.moon.location/shared_prefs/config.xml"
    }
    @Volatile private var cached: ConfigSnapshot? = null
    @Volatile private var lastRead = 0L
    @Volatile private var rawPresent = false
    @Volatile private var error: String? = null
    @Volatile private var readCount = 0L
    @Volatile private var channel: String = "none"
    private val lock = Any()

    private val prefs by lazy { runCatching { module.getRemotePreferences(Keys.GROUP) }.getOrNull() }

    /** Whether a snapshot string was present at the last read. */
    fun configReadable(): Boolean = rawPresent

    /** Last read/parse error, or null. */
    fun lastError(): String? = error

    /** Number of refresh() reads attempted. */
    fun reads(): Long = readCount

    /** Which channel produced the current snapshot: "lsposed" | "file" | "none". */
    fun channel(): String = channel

    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRead < MIN_INTERVAL_MS) return
        synchronized(lock) {
            if (SystemClock.elapsedRealtime() - lastRead < MIN_INTERVAL_MS) return
            lastRead = now
            readCount++

            var raw: String? = null
            var src = "none"

            // Channel 0 (primary): root-written shared JSON file.
            val fromShared = readWholeFile(Keys.SHARED_SNAPSHOT_PATH)
            if (!fromShared.isNullOrBlank()) {
                raw = fromShared
                src = "shared"
            }

            // Channel 1: LSPosed remote preferences.
            if (raw.isNullOrBlank()) {
                try {
                    val p = prefs?.getString(Keys.KEY_SNAPSHOT, null)
                    if (!p.isNullOrBlank()) {
                        raw = p
                        src = "lsposed"
                    }
                } catch (t: Throwable) {
                    error = "prefs: ${t.javaClass.simpleName}"
                }
            }

            // Channel 2: read the app's prefs XML directly (root/system).
            if (raw.isNullOrBlank()) {
                val fromFile = readSnapshotFromPrefsXml()
                if (!fromFile.isNullOrBlank()) {
                    raw = fromFile
                    src = "prefsfile"
                }
            }

            rawPresent = !raw.isNullOrBlank()
            val parsed = ConfigSnapshot.fromJson(raw)
            if (parsed != null && (cached == null || parsed.revision >= cached!!.revision)) {
                cached = parsed
                channel = src
                error = null
            } else if (parsed == null && rawPresent) {
                error = "parse failed"
            } else if (parsed == null) {
                if (channel == "none") error = "no snapshot ($PREFS_FILE?)"
            }
        }
    }

    /** Read a whole file as UTF-8, or null. */
    private fun readWholeFile(path: String): String? {
        val f = File(path)
        if (!f.canRead()) return null
        return try {
            FileInputStream(f).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) {
            null
        }
    }

    /** Parse `config.xml` and pull out the `<string name="snapshot">...</string>`. */
    private fun readSnapshotFromPrefsXml(): String? {
        val text = readWholeFile(PREFS_FILE) ?: run {
            if (error == null) error = "cannot read $PREFS_FILE"
            return null
        }
        return try {
            val marker = "<string name=\"${Keys.KEY_SNAPSHOT}\">"
            val start = text.indexOf(marker)
            if (start < 0) return null
            val end = text.indexOf("</string>", start + marker.length)
            if (end < 0) return null
            text.substring(start + marker.length, end)
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
        } catch (t: Throwable) {
            error = "file: ${t.javaClass.simpleName}"
            null
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
