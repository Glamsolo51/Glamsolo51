package com.glamsolo.yetipizza

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class SavedOrder(val id: Long, val token: String)

/** Small things the app remembers between launches: cart, contact details, own orders, staff session. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("yeti", Context.MODE_PRIVATE)

    var cart: Map<String, Int>
        get() = runCatching {
            val j = JSONObject(p.getString("cart", "{}")!!)
            j.keys().asSequence().associateWith { j.getInt(it) }
        }.getOrDefault(emptyMap())
        set(value) = p.edit { putString("cart", JSONObject(value as Map<*, *>).toString()) }

    var name: String
        get() = p.getString("name", "")!!
        set(v) = p.edit { putString("name", v) }
    var phone: String
        get() = p.getString("phone", "")!!
        set(v) = p.edit { putString("phone", v) }
    var email: String
        get() = p.getString("email", "")!!
        set(v) = p.edit { putString("email", v) }

    var orders: List<SavedOrder>
        get() = runCatching {
            val a = JSONArray(p.getString("orders", "[]"))
            (0 until a.length()).map { SavedOrder(a.getJSONObject(it).getLong("id"), a.getJSONObject(it).getString("token")) }
        }.getOrDefault(emptyList())
        set(v) = p.edit { putString("orders", JSONArray(v.take(5).map { JSONObject().put("id", it.id).put("token", it.token) }).toString()) }

    var adminToken: String?
        get() = p.getString("adminToken", null)
        set(v) = p.edit { putString("adminToken", v) }

    /** Whether this device keeps ringing about new orders in the background. */
    var watchOrders: Boolean
        get() = p.getBoolean("watchOrders", false)
        set(v) = p.edit { putBoolean("watchOrders", v) }

    var lastSeenOrderId: Long
        get() = p.getLong("lastSeenOrderId", 0)
        set(v) = p.edit { putLong("lastSeenOrderId", v) }
}
