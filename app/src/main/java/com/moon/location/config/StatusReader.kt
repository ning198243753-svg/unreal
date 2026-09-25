package com.moon.location.config

import android.content.Context
import android.location.LocationManager
import org.json.JSONObject

/**
 * App-side reader for the system_server status probe.
 *
 * Calls `getLastKnownLocation(PROBE_PROVIDER)`; the module (running in
 * system_server) intercepts that call and returns a Location whose extras carry
 * a JSON status blob. Never touches a real provider.
 */
object StatusReader {

    fun read(ctx: Context): JSONObject? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val loc = try {
            lm.getLastKnownLocation(Keys.PROBE_PROVIDER)
        } catch (_: Throwable) {
            null
        } ?: return null
        val json = loc.extras?.getString(Keys.PROBE_EXTRA_STATE) ?: return null
        return try {
            JSONObject(json)
        } catch (_: Throwable) {
            null
        }
    }
}
