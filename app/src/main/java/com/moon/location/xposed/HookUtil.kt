package com.moon.location.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/** Reflection + hook helpers for the libxposed module side. */
object HookUtil {

    /**
     * Try a list of fully-qualified class names in order and return the first that loads.
     * AOSP moved these classes across releases, and OEMs add their own variants.
     */
    @JvmStatic
    fun findClass(loader: ClassLoader, vararg names: String): Class<*>? {
        for (name in names) {
            try {
                return Class.forName(name, false, loader)
            } catch (_: Throwable) {
                // try next candidate
            }
        }
        return null
    }

    /** Find a declared method anywhere in the hierarchy (private methods included). */
    @JvmStatic
    fun findMethod(clazz: Class<*>, name: String, vararg params: Class<*>): java.lang.reflect.Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *params).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return null
    }

    /** Find a declared method by name + arity (used when exact param types are unknown). */
    @JvmStatic
    fun findMethodByArity(clazz: Class<*>, name: String, arity: Int): java.lang.reflect.Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == arity) {
                    return m.apply { isAccessible = true }
                }
            }
            c = c.superclass
        }
        return null
    }

    /** Find a declared field anywhere in the hierarchy and make it accessible. */
    @JvmStatic
    fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    @JvmStatic
    fun fieldValue(target: Any, name: String): Any? =
        runCatching { findField(target.javaClass, name)?.get(target) }.getOrNull()

    /** system_server's application Context, for permission checks. */
    @JvmStatic
    fun systemContext(): android.content.Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
        at.getDeclaredMethod("getSystemContext").invoke(current) as? android.content.Context
    }.getOrNull()

    @JvmStatic
    fun callMethod(target: Any, name: String, vararg args: Any?): Any? {
        val m = findMethodByArity(target.javaClass, name, args.size) ?: return null
        return runCatching { m.invoke(target, *args) }.getOrNull()
    }

    /** Hook every overload of [methodName] declared on [clazz] with [hooker]. */
    @JvmStatic
    fun hookAll(
        module: XposedModule,
        tag: String,
        clazz: Class<*>,
        methodName: String,
        hooker: XposedInterface.Hooker,
    ): Int {
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            module.log(Log.WARN, tag, "no method $methodName on ${clazz.name}")
            return 0
        }
        var hooked = 0
        for (m in methods) {
            try {
                module.hook(m).intercept(hooker)
                hooked++
            } catch (t: Throwable) {
                module.log(Log.ERROR, tag, "hook ${clazz.name}#$methodName failed: ${t.message}")
            }
        }
        module.log(Log.INFO, tag, "hooked ${clazz.simpleName}#$methodName ($hooked overloads)")
        return hooked
    }

    /** Hook every constructor of [clazz]; [onInstance] runs after the instance exists. */
    @JvmStatic
    fun hookConstructors(
        module: XposedModule,
        tag: String,
        clazz: Class<*>,
        onInstance: (Any) -> Unit,
    ): Int {
        var hooked = 0
        for (ctor in clazz.declaredConstructors) {
            try {
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    runCatching { onInstance(chain.thisObject) }
                    result
                }
                hooked++
            } catch (t: Throwable) {
                module.log(Log.ERROR, tag, "hook ctor ${clazz.name} failed: ${t.message}")
            }
        }
        return hooked
    }
}
