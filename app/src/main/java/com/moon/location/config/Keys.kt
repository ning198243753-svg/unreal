package com.moon.location.config

/**
 * Cross-process configuration contract shared by the app (writer) and the
 * system_server hooks (reader) via LSPosed remote preferences.
 *
 * The app writes to `getSharedPreferences(GROUP, MODE_PRIVATE)`. The module side
 * reads the same group with `XposedModule.getRemotePreferences(GROUP)`.
 */
object Keys {
    const val GROUP = "config"

    /** Single JSON-encoded snapshot value key. */
    const val KEY_SNAPSHOT = "snapshot"

    /** Inverse version of the raw app-side preferences, for the module-active probe. */
    const val KEY_MODULE_VERSION = "module_version"
}
