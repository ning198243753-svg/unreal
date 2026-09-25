package com.moon.location

/**
 * Probe the system_server hook replaces when this app is added to the module scope.
 * Without the hook it returns false, so the UI shows a hint to check LSPosed scope.
 */
object ModuleStatus {
    @JvmStatic
    fun isModuleActive(): Boolean = false
}
