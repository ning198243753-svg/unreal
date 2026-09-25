package com.moon.location.xposed

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.moon.location.config.Keys
import io.github.libxposed.api.XposedModule
import org.json.JSONObject

/**
 * system_server hooks. This is the core of the module: every provider report
 * funnels through [LocationProviderManager], so rewriting there covers every app
 * that uses the system location API.
 *
 * Verified against realme GT7 Pro (Android 16, services.jar):
 *  - com.android.server.location.LocationManagerService
 *  - com.android.server.location.provider.LocationProviderManager
 *    (methods: onReportLocation / onReportLocations / getLastLocation /
 *     getLastLocationUnsafe / getCurrentLocation / addTestProvider /
 *     setTestProviderLocation; nested *Registration has acceptLocationChange)
 *  - OEM: com.android.server.location.OplusLocationManagerService
 */
class SystemHooks(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val state: SpoofState,
) {
    private companion object {
        const val TAG = "MoonLocation.SystemHooks"
        const val MODULE_PKG = "com.moon.location"
    }

    /** Set after the pump is created so the probe can report its stats. */
    @Volatile private var pump: Pump? = null

    /** Diagnostics for the status probe. */
    private val reportHits = java.util.concurrent.atomic.AtomicLong(0)
    private val rewriteHits = java.util.concurrent.atomic.AtomicLong(0)
    private val activeNullHits = java.util.concurrent.atomic.AtomicLong(0)
    private val argNullHits = java.util.concurrent.atomic.AtomicLong(0)
    private val fieldNullHits = java.util.concurrent.atomic.AtomicLong(0)
    private val listNullHits = java.util.concurrent.atomic.AtomicLong(0)
    private val emptyListHits = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var lastArgClass: String = "-"
    @Volatile private var lastListSize: Int = -1

    fun attachPump(pump: Pump) {
        this.pump = pump
    }

    fun install() {
        hookLastLocation()
        hookReportLocation()
        module.log(Log.INFO, TAG, "system hooks installed")
    }

    /**
     * Choke point for every provider report. `LocationResult` exposes an
     * `ArrayList<Location> mLocations` (verified on this ROM); we rewrite each
     * Location in place BEFORE the framework dispatches (dispatch happens inside
     * proceed).
     *
     * Also captures the live LocationProviderManager instance for the pump.
     */
    private fun hookReportLocation() {
        val cls = HookUtil.findClass(
            loader,
            "com.android.server.location.provider.LocationProviderManager",
        ) ?: run {
            module.log(Log.ERROR, TAG, "LocationProviderManager not found")
            return
        }

        val n = HookUtil.hookAll(module, TAG, cls, "onReportLocation") { chain ->
            // Capture the manager instance from the live call path.
            pump?.registerManager(chain.thisObject)
            reportHits.incrementAndGet()
            val arg = chain.args.firstOrNull()
            if (arg == null) {
                argNullHits.incrementAndGet()
            } else {
                lastArgClass = arg.javaClass.name
            }
            val snap = state.active()
            if (snap == null) {
                activeNullHits.incrementAndGet()
            } else {
                when (rewriteLocationResult(arg, snap)) {
                    1 -> rewriteHits.incrementAndGet()
                    2 -> fieldNullHits.incrementAndGet()
                    3 -> listNullHits.incrementAndGet()
                    4 -> emptyListHits.incrementAndGet()
                }
            }
            chain.proceed()
        }
        module.log(Log.INFO, TAG, "onReportLocation hooks=$n")
    }

    /**
     * `getLastKnownLocation` path, so a lone last-fix is spoofed too.
     * Also the readback channel: `getLastLocation(<probe>)` returns a status Location.
     */
    private fun hookLastLocation() {
        val cls = HookUtil.findClass(
            loader,
            "com.android.server.location.LocationManagerService",
            "com.android.server.location.LocationManagerService",
            "com.android.server.location.OplusLocationManagerService",
        ) ?: run {
            module.log(Log.WARN, TAG, "LocationManagerService not found")
            return
        }

        HookUtil.hookAll(module, TAG, cls, "getLastLocation") { chain ->
            // Capture the live LocationManagerService instance (for mProviderManagers).
            pump?.attachLms(chain.thisObject)
            val provider = requestedProvider(chain.args)
            if (provider == Keys.PROBE_PROVIDER && isProbeAllowed()) {
                buildProbeLocation()
            } else {
                val result = chain.proceed()
                val snap = state.active()
                if (snap != null) {
                    (result as? Location)?.let { LocationFactory.applyTo(it, snap) }
                }
                result
            }
        }
    }

    /** Only our own app (or system) may read the status probe back. */
    private fun isProbeAllowed(): Boolean {
        val callingUid = android.os.Binder.getCallingUid()
        if (callingUid == android.os.Process.myUid()) return true
        val ctx = HookUtil.systemContext() ?: return true // fail-open when context unavailable
        return try {
            val ai = ctx.packageManager.getApplicationInfo(MODULE_PKG, 0)
            ai.uid == callingUid
        } catch (_: Throwable) {
            true
        }
    }

    /** Build the status-probe Location returned to our own app (no permission needed). */
    private fun buildProbeLocation(): Location {
        val loc = Location(Keys.PROBE_PROVIDER)
        val pump = this.pump
        val json = JSONObject().apply {
            put("module", "unreal")
            put("sdk", Build.VERSION.SDK_INT)
            put("configReadable", state.configReadable())
            put("reads", state.reads())
            put("error", state.lastError() ?: JSONObject.NULL)
            put("snapshot", state.current()?.let { snap ->
                JSONObject().apply {
                    put("revision", snap.revision)
                    put("started", snap.started)
                    put("lat", snap.lat)
                    put("lng", snap.lng)
                }
            } ?: JSONObject.NULL)
            put("pumpReady", pump?.isReady ?: false)
            put("pumpDelivery", pump?.deliveryName ?: JSONObject.NULL)
            put("pumpManagers", pump?.managerCount ?: 0)
            put("injected", pump?.injectedCount ?: 0L)
            put("reportHits", reportHits.get())
            put("rewriteHits", rewriteHits.get())
            put("dActiveNull", activeNullHits.get())
            put("dArgNull", argNullHits.get())
            put("dFieldNull", fieldNullHits.get())
            put("dListNull", listNullHits.get())
            put("dEmptyList", emptyListHits.get())
            put("lastArgClass", lastArgClass)
            put("lastListSize", lastListSize)
        }
        val b = Bundle()
        b.putString(Keys.PROBE_EXTRA_STATE, json.toString())
        loc.extras = b
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        return loc
    }

    /** Extract the requested provider name from getLastLocation args (String or LocationRequest). */
    private fun requestedProvider(args: List<Any?>?): String? {
        args ?: return null
        args.firstOrNull()?.let { if (it is String) return it }
        for (a in args) {
            if (a == null) continue
            if (a.javaClass.name.endsWith("LocationRequest") ||
                a.javaClass.name.endsWith("LastLocationRequest")
            ) {
                (HookUtil.callMethod(a, "getProvider") as? String)?.let { return it }
                (HookUtil.fieldValue(a, "mProvider") as? String)?.let { return it }
            }
        }
        return null
    }

    /**
     * Reflect into `LocationResult.mLocations` (an `ArrayList<Location>` on this ROM).
     * Returns: 1 = rewrote >=1, 2 = field not found, 3 = field not a MutableList, 4 = empty list.
     */
    private fun rewriteLocationResult(
        result: Any?,
        snap: com.moon.location.config.ConfigSnapshot,
    ): Int {
        if (result == null) return 2
        return try {
            val listField = HookUtil.findField(result.javaClass, "mLocations") ?: return 2
            @Suppress("UNCHECKED_CAST")
            val list = listField.get(result) as? MutableList<Any?> ?: return 3
            lastListSize = list.size
            if (list.isEmpty()) return 4
            var changed = false
            for (i in list.indices) {
                (list[i] as? Location)?.let {
                    LocationFactory.applyTo(it, snap)
                    changed = true
                }
            }
            if (changed) 1 else 4
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "rewrite LocationResult failed: ${t.message}")
            2
        }
    }
}
