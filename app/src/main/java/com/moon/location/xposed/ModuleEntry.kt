package com.moon.location.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed (API 101) entry point, referenced from
 * `META-INF/xposed/java_init.list`.
 *
 * M1 only installs system_server hooks. Per-app hooks (WiFi / cell / GNSS / mock
 * flag) will be added in later milestones.
 */
class ModuleEntry : XposedModule() {
    private companion object {
        const val TAG = "MoonLocation"
    }

    @Volatile private var systemState: SpoofState? = null
    @Volatile private var systemPump: Pump? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        // Let our own app detect that the module is active (requires self in scope).
        if (param.packageName == "com.moon.location") {
            runCatching {
                val cls = Class.forName("com.moon.location.ModuleStatus", false, param.classLoader)
                val m = cls.getDeclaredMethod("isModuleActive")
                hook(m).intercept { true as Any }
                log(Log.INFO, TAG, "ModuleStatus probe hooked")
            }.onFailure { log(Log.WARN, TAG, "probe hook failed: ${it.message}") }
        }
        // App-level hooks land here in M4 (WiFi/cell/GNSS privacy + mock flag).
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "onSystemServerStarting")
        val state = SpoofState(this)
        systemState = state
        var hooks: SystemHooks? = null
        try {
            hooks = SystemHooks(this, param.classLoader, state)
            hooks.install()
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "install system hooks failed: $t")
        }
        try {
            val pump = Pump(this, param.classLoader, state)
            systemPump = pump
            pump.install()
            hooks?.attachPump(pump)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "install pump failed: $t")
        }
    }
}
