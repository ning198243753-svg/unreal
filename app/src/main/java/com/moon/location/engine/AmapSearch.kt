package com.moon.location.engine

import android.content.Context
import com.moon.location.BuildConfig
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AMap (高德) Web-service REST search. Coordinates returned by AMap are GCJ-02;
 * the map page converts them to WGS-84 before reporting a pick.
 */
object AmapSearch {

    data class Poi(val name: String, val lat: Double, val lng: Double, val address: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** True when a Web-service key was baked into this build. */
    fun hasKey(): Boolean = BuildConfig.AMAP_WEB_KEY.isNotBlank()

    fun search(@Suppress("UNUSED_PARAMETER") ctx: Context, query: String): List<Poi> {
        val key = BuildConfig.AMAP_WEB_KEY
        if (key.isBlank() || query.isBlank()) return emptyList()

        val url: HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("restapi.amap.com")
            .addPathSegment("v3").addPathSegment("place").addPathSegment("text")
            .addQueryParameter("key", key)
            .addQueryParameter("keywords", query)
            .addQueryParameter("offset", "20")
            .addQueryParameter("page", "1")
            .build()

        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val body = resp.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                if (json.optString("status") != "1") return emptyList()
                val pois = json.optJSONArray("pois") ?: return emptyList()
                val out = ArrayList<Poi>(pois.length())
                for (i in 0 until pois.length()) {
                    val p = pois.optJSONObject(i) ?: continue
                    val loc = p.optString("location") // "lng,lat"
                    val parts = loc.split(',')
                    if (parts.size != 2) continue
                    val lng = parts[0].toDoubleOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull() ?: continue
                    val address = buildString {
                        val area = p.optString("pname", "") + p.optString("cityname", "") +
                            p.optString("adname", "")
                        if (area.isNotBlank()) append(area)
                        val addr = p.optString("address")
                        if (addr is String && addr.isNotBlank() && addr != "[]") append(addr)
                    }
                    out.add(Poi(p.optString("name"), lat, lng, address))
                }
                out
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Serialise results for the map page (GCJ-02 lng/lat). */
    fun toJson(pois: List<Poi>): String {
        val arr = org.json.JSONArray()
        for (p in pois) {
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("lat", p.lat)
                put("lng", p.lng)
                put("address", p.address)
            })
        }
        return arr.toString()
    }
}
