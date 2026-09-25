package com.moon.location.xposed

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.moon.location.config.Keys
import io.github.libxposed.api.XposedModule

/**
 * App-process hooks. Some OEMs expose a client-side wrapper
 * (`android.location.LocationManagerExtImpl` on realme/OPLUS) that keeps its own
 * last-location cache and is what apps like AMap actually call. That cache lives
 * in the app process, so it cannot be fixed from system_server alone.
 *
 * These hooks run inside the target app. They cannot read the shared snapshot file
 * (SELinux denies untrusted_app -> system_data_file), so they fetch the current
 * spoofed coordinates through the system location API using the POS probe provider,
 * which the system_server hook answers.
 */
class AppHooks(
    private val module: XposedModule,
    private val loader: ClassLoader,
) {
    private companion object {
        const val TAG = "MoonLocation.AppHooks"
    }

    @Volatile private var lm: LocationManager? = null
    @Volatile private var lastFetch = 0L
    @Volatile private var cachedSpoof: Location? = null

    fun install() {
        hookOemExt()
        module.log(Log.INFO, TAG, "app hooks installed")
    }

    /** Ask system_server for the current spoofed fix via the POS provider. */
    private fun spoofFix(): Location? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFetch < 1000L && cachedSpoof != null) return cachedSpoof
        lastFetch = now
        return try {
            val ctx = currentApplication() ?: return cachedSpoof
            val manager = lm ?: (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.also { lm = it } ?: return cachedSpoof
            val loc = manager.getLastKnownLocation(Keys.POS_PROVIDER)
            if (loc != null) cachedSpoof = loc
            cachedSpoof
        } catch (t: Throwable) {
            cachedSpoof
        }
    }

    private fun currentApplication(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
        val app = at.getDeclaredMethod("getApplication").invoke(current)
        app as? Context
    }.getOrNull()

    private fun applySpoof(original: Location?): Location? {
        val fake = spoofFix() ?: return original
        val out = original ?: Location(fake.provider ?: "gps")
        out.latitude = fake.latitude
        out.longitude = fake.longitude
        if (fake.accuracy > 0f) out.accuracy = fake.accuracy
        out.time = System.currentTimeMillis()
        out.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        runCatching {
            Location::class.java.getMethod("setIsFromMockProvider", Boolean::class.javaPrimitiveType)
                .invoke(out, false)
        }
        return out
    }

    private fun hookOemExt() {
        for (name in listOf(
            "android.location.LocationManagerExtImpl",
            "com.oplus.location.LocationManagerExtImpl",
        )) {
            val cls = HookUtil.findClass(loader, name) ?: continue
            module.log(Log.INFO, TAG, "hooking OEM ext $name")
            HookUtil.hookAll(module, TAG, cls, "getLastLocation") { chain ->
                val result = chain.proceed()
                applySpoof(result as? Location)
            }
            // saveLastLocation(Context, Location, ...) keeps the OEM cache; rewrite it.
            for (m in cls.declaredMethods) {
                if (m.name == "saveLastLocation") {
                    runCatching {
                        module.hook(m).intercept { chain ->
                            val idx = chain.args.indexOfFirst { it is Location }
                            if (idx >= 0) {
                                applySpoof(chain.args[idx] as? Location)
                            }
                            chain.proceed()
                        }
                    }.onFailure { module.log(Log.WARN, TAG, "hook saveLastLocation failed: ${it.message}") }
                }
            }
        }
    }
}
