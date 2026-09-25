package com.moon.location.xposed

import android.location.Location
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * App-process hooks. Target apps (WeChat, AMap, ...) frequently take location
 * through the client-side `android.location.LocationManager` inside their own
 * process, and some OEMs wrap it (`LocationManagerExtImpl` on OPLUS). The
 * system_server rewrite alone does not always reach them, so we also rewrite
 * here in the app process.
 *
 * Hooks (best-effort, reflection-based, name+arity matching):
 *  - android.location.LocationManager#getLastKnownLocation(String)
 *  - android.location.LocationManager#getCurrentLocation(...)
 *  - android.location.LocationManager#requestLocationUpdates(...)  (rewrite args? no:
 *    its callbacks arrive via LocationListener; we instead hook LocationListener.onLocationChanged)
 *  - <OEM>.LocationManagerExtImpl#getLastLocation(...)
 *
 * The spoofed value comes from the shared snapshot (SpoofState).
 */
class AppHooks(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val state: SpoofState,
) {
    private companion object {
        const val TAG = "MoonLocation.AppHooks"
    }

    fun install() {
        hookLocationManager()
        hookLocationListener()
        hookOemExt()
        module.log(Log.INFO, TAG, "app hooks installed")
    }

    private fun spoofIfActive(original: Location?): Location? {
        val snap = state.active() ?: return original
        val base = original ?: return LocationFactory.create(snap)
        LocationFactory.applyTo(base, snap)
        return base
    }

    private fun hookLocationManager() {
        val cls = HookUtil.findClass(loader, "android.location.LocationManager") ?: run {
            module.log(Log.WARN, TAG, "LocationManager not found")
            return
        }
        HookUtil.hookAll(module, TAG, cls, "getLastKnownLocation") { chain ->
            val result = chain.proceed()
            spoofIfActive(result as? Location)
        }
        // getCurrentLocation(String, CancellationSignal, Executor, Consumer) -> void
        HookUtil.hookAll(module, TAG, cls, "getCurrentLocation") { chain ->
            chain.proceed()
        }
    }

    /** Rewrite the Location handed to app callbacks. */
    private fun hookLocationListener() {
        val cls = HookUtil.findClass(loader, "android.location.LocationListener") ?: return
        for (m in cls.declaredMethods) {
            if (m.name == "onLocationChanged" && m.parameterCount == 1 &&
                m.parameterTypes[0].name == "android.location.Location"
            ) {
                runCatching {
                    module.hook(m).intercept { chain ->
                        val arg = chain.args.firstOrNull() as? Location
                        if (arg != null) {
                            spoofIfActive(arg)
                            chain.proceedWith(arg, chain.args.toTypedArray())
                        } else {
                            chain.proceed()
                        }
                    }
                }.onFailure { module.log(Log.WARN, TAG, "hook LocationListener failed: ${it.message}") }
            }
        }
    }

    /** OPLUS wraps LocationManager in android.location.LocationManagerExtImpl (App process). */
    private fun hookOemExt() {
        for (name in listOf(
            "android.location.LocationManagerExtImpl",
            "com.oplus.location.LocationManagerExtImpl",
            "com.android.server.location.LocationManagerExtImpl",
        )) {
            val cls = HookUtil.findClass(loader, name) ?: continue
            module.log(Log.INFO, TAG, "hooking OEM ext $name")
            HookUtil.hookAll(module, TAG, cls, "getLastLocation") { chain ->
                val result = chain.proceed()
                spoofIfActive(result as? Location)
            }
            HookUtil.hookAll(module, TAG, cls, "getLastKnownLocation") { chain ->
                val result = chain.proceed()
                spoofIfActive(result as? Location)
            }
            // saveLastLocation(Context, Location, String, String, String): rewrite the
            // cached Location argument so the OEM cache never stores a real fix.
            for (m in cls.declaredMethods) {
                if (m.name == "saveLastLocation") {
                    runCatching {
                        module.hook(m).intercept { chain ->
                            val idx = chain.args.indexOfFirst { it is Location }
                            val snap = state.active()
                            if (idx >= 0 && snap != null) {
                                (chain.args[idx] as? Location)?.let { LocationFactory.applyTo(it, snap) }
                            }
                            chain.proceed()
                        }
                    }.onFailure { module.log(Log.WARN, TAG, "hook saveLastLocation failed: ${it.message}") }
                }
            }
        }
    }
}
