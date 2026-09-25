package com.moon.location.xposed

import android.location.Location
import android.util.Log
import com.moon.location.config.ConfigSnapshot
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * system_server hooks. This is the core of the module: every provider report
 * funnels through [com.android.server.location.provider.LocationProviderManager],
 * so rewriting there covers every app that uses the system location API.
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
    }

    fun install() {
        hookReportLocation()
        hookLastLocation()
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

    /** `getLastKnownLocation` path, so a lone last-fix is spoofed too. */
    private fun hookLastLocation() {
        val cls = HookUtil.findClass(
            loader,
            "com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService",
            "com.android.server.location.OplusLocationManagerService",
        ) ?: run {
            module.log(Log.WARN, TAG, "LocationManagerService not found")
            return
        }

        HookUtil.hookAll(module, TAG, cls, "getLastLocation") { chain ->
            val result = chain.proceed()
            val snap = state.active()
            if (snap != null) {
                (result as? Location)?.let { LocationFactory.applyTo(it, snap) }
            }
            result
        }
    }

    /**
     * Reflect into `LocationResult.mLocations` (or `mLocations` on `onReportLocations`).
     * Field names are stable across Android 12–16.
     */
    private fun rewriteLocationResult(result: Any?, snap: ConfigSnapshot) {
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
