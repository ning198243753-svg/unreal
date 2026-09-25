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

    fun attachPump(pump: Pump) {
        this.pump = pump
    }

    fun install() {
        hookLastLocation()
        hookReportLocation()
        module.log(Log.INFO, TAG, "system hooks installed")
    }

    /**
     * Choke point for every provider report. `LocationResult` exposes a
     * `mLocations` list; we rewrite each Location in place so that whatever
     * downstream registration / cache receives is already spoofed.
     */
    private fun hookReportLocation() {
        val cls = HookUtil.findClass(
            loader,
            "com.android.server.location.provider.LocationProviderManager",
        ) ?: run {
            module.log(Log.ERROR, TAG, "LocationProviderManager not found")
            return
        }

        HookUtil.hookAll(module, TAG, cls, "onReportLocation") { chain ->
            val result = chain.proceed()
            val snap = state.active()
            if (snap != null) {
                rewriteLocationResult(chain.args.firstOrNull(), snap)
            }
            result
        }
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
        val ctx = HookUtil.systemContext() ?: return false
        return try {
            val ai = ctx.packageManager.getApplicationInfo(MODULE_PKG, 0)
            ai.uid == callingUid
        } catch (_: Throwable) {
            false
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
     * Reflect into `LocationResult.mLocations`; field name is stable across Android 12–16.
     */
    private fun rewriteLocationResult(result: Any?, snap: com.moon.location.config.ConfigSnapshot) {
        if (result == null) return
        try {
            val listField = HookUtil.findField(result.javaClass, "mLocations") ?: return
            @Suppress("UNCHECKED_CAST")
            val list = listField.get(result) as? MutableList<Any?> ?: return
            for (i in list.indices) {
                (list[i] as? Location)?.let { LocationFactory.applyTo(it, snap) }
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "rewrite LocationResult failed: ${t.message}")
        }
    }
}
