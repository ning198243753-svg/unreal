package com.moon.location.xposed

import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.moon.location.config.ConfigSnapshot
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Function

/**
 * system_server-side continuous driver ("direct push").
 *
 * The passive hooks in [SystemHooks] only rewrite locations that a real provider
 * reports. Indoors, with no GNSS fix and network positioning masked, no report may
 * arrive, so a target app sees nothing. This pump hands a fresh spoofed fix
 * directly to every location registration at a fixed cadence, exactly where a
 * provider report would be delivered:
 *
 *   LocationProviderManager (a ListenerMultiplexer)
 *     .deliverToListeners(registration -> registration.acceptLocationChange(result))
 *
 * `LocationResult` is a @SystemApi class (absent from the public SDK), so it is
 * constructed reflectively via `LocationResult.wrap(Location[])`.
 *
 * The real providers keep running untouched; only the spoofed fix is injected.
 */
class Pump(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val state: SpoofState,
) {
    private companion object {
        const val TAG = "MoonLocation.Pump"
        const val INTERVAL_MS = 1000L
        const val HEARTBEAT_REFRESH_MS = 2000L
        val PROVIDERS = arrayOf("gps", "network", "fused", "passive")
    }

    @Volatile private var delivery: Method? = null
    @Volatile private var locationResultClass: Class<*>? = null
    @Volatile private var wrapMethod: Method? = null
    @Volatile private var lmsInstance: Any? = null

    private val managers = ConcurrentHashMap<String, Any>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var lastKey: String? = null
    @Volatile private var lastSent = 0L
    private val injected = AtomicLong(0)

    /** Number of fixes injected so far (for status readback). */
    val injectedCount: Long get() = injected.get()

    /** Providers whose manager instance we captured. */
    val managerCount: Int get() = managers.size

    /** Name of the resolved delivery method, or null if not found. */
    val deliveryName: String? get() = delivery?.name

    /** True when the reflective pieces were all found on this ROM. */
    @Volatile var isReady = false
        private set

    fun install() {
        val managerClass = HookUtil.findClass(
            loader,
            "com.android.server.location.provider.LocationProviderManager",
        )
        val lmsClass = HookUtil.findClass(
            loader,
            "com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService",
        )
        locationResultClass = HookUtil.findClass(loader, "android.location.LocationResult")
        // NOTE: real ROM signature is wrap(Location[]) (varargs).
        wrapMethod = locationResultClass?.let {
            HookUtil.findMethod(it, "wrap", Array<Location>::class.java)
        }

        // IMPORTANT: onSystemServerStarting runs BEFORE LocationManagerService and its
        // provider managers are constructed, so ctor hooks are unreliable. Instead we
        // capture instances lazily from the live call path (SystemHooks calls attachLms /
        // captureManager), plus we still install ctor hooks as a best-effort bonus.
        managerClass?.let { cls ->
            findDelivery(cls)?.let { delivery = it }
            HookUtil.hookConstructors(module, TAG, cls) { inst -> registerManager(inst) }
        }
        lmsClass?.let { cls ->
            HookUtil.hookConstructors(module, TAG, cls) { inst -> lmsInstance = inst }
        }

        isReady = delivery != null && wrapMethod != null
        module.log(
            Log.INFO,
            TAG,
            "pump ready=$isReady delivery=${delivery?.name} wrap=${wrapMethod != null} resultClass=${locationResultClass != null}",
        )
        start()
    }

    /** Called from the live hook path when a LocationProviderManager instance is seen. */
    fun registerManager(manager: Any) {
        runCatching {
            managerName(manager)?.let { managers[it] = manager }
        }
    }

    /** Called from the live hook path when the LocationManagerService instance is seen. */
    fun attachLms(service: Any) {
        lmsInstance = service
        promoteManagers()
    }

    /** Promote providers from LocationManagerService.mProviderManagers into our map. */
    private fun promoteManagers() {
        val service = lmsInstance ?: return
        val list = HookUtil.fieldValue(service, "mProviderManagers") as? Iterable<*> ?: return
        for (m in list) if (m != null) registerManager(m)
    }

    private fun start() {
        if (thread != null) return
        HandlerThread("MoonLocationPump").also {
            it.start()
            thread = it
            handler = Handler(it.looper)
        }
        handler?.post(pumpRunnable)
    }

    private val pumpRunnable = object : Runnable {
        override fun run() {
            runCatching { tick() }
                .onFailure { module.log(Log.WARN, TAG, "pump tick failed: ${it.message}") }
            handler?.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun tick() {
        val snap = state.active() ?: return
        val now = SystemClock.elapsedRealtime()
        val key = "${snap.revision}|${snap.lat}|${snap.lng}|${snap.alt}|${snap.accuracy}"
        // Re-push when the target moved or at least every HEARTBEAT_REFRESH_MS.
        if (key == lastKey && now - lastSent < HEARTBEAT_REFRESH_MS) return
        val n = inject(snap)
        lastKey = key
        lastSent = now
        if (n > 0) {
            injected.addAndGet(n.toLong())
            module.log(Log.VERBOSE, TAG, "injected $n fixes")
        }
    }

    private fun inject(snap: ConfigSnapshot): Int {
        ensureManagers()
        val wrap = wrapMethod ?: return 0
        val deliver = delivery ?: return 0
        var total = 0
        for (provider in PROVIDERS) {
            val mgr = managers[provider] ?: continue
            val fix = LocationFactory.create(snap, provider)
            val result = try {
                wrap.invoke(null, arrayOf<Any?>(arrayOf(fix)))
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "wrap failed for $provider: ${t.message}")
                continue
            } ?: continue
            total += deliverTo(mgr, deliver, result)
        }
        return total
    }

    private fun deliverTo(manager: Any, deliver: Method, result: Any): Int {
        val counter = intArrayOf(0)
        val function: Any = Proxy.newProxyInstance(
            loader,
            arrayOf(Function::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "apply" -> {
                        val registration = args?.firstOrNull()
                        if (registration != null) {
                            val op = HookUtil.callMethod(registration, "acceptLocationChange", result)
                            if (op != null) counter[0]++
                        }
                        null
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "MoonLocationPump"
                    else -> null
                }
            },
        )
        return try {
            deliver.invoke(manager, function)
            counter[0]
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "deliverToListeners failed: ${t.message}")
            0
        }
    }

    /** LocationProviderManager extends ListenerMultiplexer#deliverToListeners(Function). */
    private fun findDelivery(cls: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == "deliverToListeners" &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0] == Function::class.java
                ) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }

    /** Recover provider managers from `LocationManagerService.mProviderManagers` if needed. */
    private fun ensureManagers() {
        if (managers.size >= PROVIDERS.size) return
        promoteManagers()
    }

    private fun managerName(manager: Any): String? {
        (HookUtil.fieldValue(manager, "mName") as? String)?.let { return it }
        return HookUtil.callMethod(manager, "getName") as? String
    }
}
