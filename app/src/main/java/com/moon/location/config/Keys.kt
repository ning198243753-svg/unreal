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

    /**
     * Sentinel "provider" name used only to read status back out of system_server.
     * The module intercepts `getLastLocation("moon.location.probe")` and returns a
     * Location carrying a JSON status blob in its extras. Never a real provider.
     */
    const val PROBE_PROVIDER = "moon.location.probe"

    /** Extras key holding the status JSON inside the probe Location. */
    const val PROBE_EXTRA_STATE = "state"

    /**
     * Root-written shared snapshot file (world-readable, shell_data_file context)
     * used as the primary cross-process channel when LSPosed remote preferences
     * read back empty (observed on this ROM).
     */
    const val SHARED_SNAPSHOT_PATH = "/data/local/tmp/moon_snapshot.json"
}
