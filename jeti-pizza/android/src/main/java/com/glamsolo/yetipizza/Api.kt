package com.glamsolo.yetipizza

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Error the server explained in words (validation, cooldown, closed) or a lost connection. */
class ApiException(
    val status: Int,
    message: String,
    val retryAfterSeconds: Int = 0,
    val field: String? = null,
) : Exception(message)

data class ShopInfo(
    val address: String,
    val phone: String,
    val open: String,
    val close: String,
    val prepMinutes: Int,
    val slots: List<String>,
    val asapAllowed: Boolean,
    val isOpen: Boolean,
    val acceptingOrders: Boolean,
    val mapUrl: String,
)

data class Category(val id: String, val name: String)

data class MenuItem(
    val id: String,
    val category: String,
    val name: String,
    val description: String,
    val unit: String,
    val price: Int,
    val available: Boolean,
)

data class Menu(val info: ShopInfo, val categories: List<Category>, val items: List<MenuItem>)

data class OrderLine(val id: String, val name: String, val unit: String, val price: Int, val qty: Int)

data class Order(
    val id: Long,
    val status: String,
    val createdAt: Long,
    val pickupAt: Long,
    val asap: Boolean,
    val name: String,
    val total: Int,
    val items: List<OrderLine>,
    // Only in the staff cabinet:
    val phone: String = "",
    val email: String = "",
    val comment: String = "",
    val source: String = "",
    // Only right after the customer placed it:
    val token: String = "",
)

data class ReportRow(val name: String, val unit: String, val category: String, val qty: Int, val revenue: Int, val share: Double)

data class Report(
    val from: String,
    val to: String,
    val orders: Int,
    val revenue: Int,
    val averageCheck: Int,
    val cancelled: Int,
    val items: List<ReportRow>,
)

data class Settings(val acceptingOrders: Boolean, val cooldownMinutes: Int)

/** Blocking client for jeti-pizza/server; call it off the main thread. */
class Api(private val baseUrl: String, var token: String? = null) {

    fun menu(): Menu = call("GET", "/api/menu").toMenu()

    fun createOrder(
        name: String,
        phone: String,
        email: String,
        pickupTime: String,
        comment: String,
        cart: Map<String, Int>,
    ): Order = call(
        "POST", "/api/orders",
        JSONObject()
            .put("name", name).put("phone", phone).put("email", email)
            .put("pickupTime", pickupTime).put("comment", comment).put("source", "app")
            .put("items", JSONArray(cart.map { (id, qty) -> JSONObject().put("id", id).put("qty", qty) })),
    ).toOrder()

    fun order(id: Long, token: String): Order = call("GET", "/api/orders/$id?token=${enc(token)}").toOrder()

    fun login(login: String, password: String): String =
        call("POST", "/api/admin/login", JSONObject().put("login", login).put("password", password)).getString("token")

    fun logout() {
        call("POST", "/api/admin/logout")
    }

    /** Active orders, or every order of [date] (yyyy-MM-dd) for the history. */
    fun orders(date: String? = null): List<Order> =
        call("GET", "/api/admin/orders" + (date?.let { "?date=$it" } ?: "")).getJSONArray("orders").map { it.toOrder() }

    fun setStatus(id: Long, status: String): Order =
        call("POST", "/api/admin/orders/$id/status", JSONObject().put("status", status)).toOrder()

    fun adminMenu(): Menu = call("GET", "/api/admin/menu").toMenu()

    fun updateItem(id: String, available: Boolean? = null, price: Int? = null): MenuItem =
        call(
            "PATCH", "/api/admin/items/${enc(id)}",
            JSONObject().apply {
                available?.let { put("available", it) }
                price?.let { put("price", it) }
            },
        ).toItem()

    fun report(from: String, to: String): Report = call("GET", "/api/admin/report?from=$from&to=$to").let { j ->
        Report(
            from = j.getString("from"), to = j.getString("to"), orders = j.getInt("orders"), revenue = j.getInt("revenue"),
            averageCheck = j.getInt("averageCheck"), cancelled = j.getInt("cancelled"),
            items = j.getJSONArray("items").map {
                ReportRow(it.getString("name"), it.getString("unit"), it.optString("category"), it.getInt("qty"), it.getInt("revenue"), it.getDouble("share"))
            },
        )
    }

    fun settings(): Settings = call("GET", "/api/admin/settings").toSettings()

    fun saveSettings(s: Settings): Settings = call(
        "PUT", "/api/admin/settings",
        JSONObject().put("acceptingOrders", s.acceptingOrders).put("cooldownMinutes", s.cooldownMinutes),
    ).toSettings()

    fun changePassword(current: String, next: String) {
        call("POST", "/api/admin/password", JSONObject().put("current", current).put("next", next))
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // HttpURLConnection has no PATCH; the server reads the override header.
            conn.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code >= 400) {
                throw ApiException(
                    code,
                    json.optString("error").ifBlank { "Ошибка сервера ($code)." },
                    retryAfterSeconds = json.optInt("retryAfter"),
                    field = json.optString("field").ifBlank { null },
                )
            }
            return json
        } catch (e: IOException) {
            throw ApiException(0, "Нет связи с пиццерией. Проверьте интернет и попробуйте ещё раз.")
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

private fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

private fun JSONObject.toItem() = MenuItem(
    getString("id"), getString("category"), getString("name"), optString("description"), optString("unit"),
    getInt("price"), getBoolean("available"),
)

private fun JSONObject.toMenu(): Menu {
    val i = getJSONObject("info")
    val slots = i.getJSONArray("slots")
    return Menu(
        info = ShopInfo(
            address = i.getString("address"), phone = i.getString("phone"), open = i.getString("open"), close = i.getString("close"),
            prepMinutes = i.getInt("prepMinutes"), slots = (0 until slots.length()).map { slots.getString(it) },
            asapAllowed = i.getBoolean("asapAllowed"), isOpen = i.getBoolean("isOpen"),
            acceptingOrders = i.getBoolean("acceptingOrders"), mapUrl = i.optString("mapUrl"),
        ),
        categories = getJSONArray("categories").map { Category(it.getString("id"), it.getString("name")) },
        items = getJSONArray("items").map { it.toItem() },
    )
}

private fun JSONObject.toOrder() = Order(
    id = getLong("id"), status = getString("status"), createdAt = getLong("createdAt"), pickupAt = getLong("pickupAt"),
    asap = getBoolean("asap"), name = getString("name"), total = getInt("total"),
    items = getJSONArray("items").map { OrderLine(it.getString("id"), it.getString("name"), it.optString("unit"), it.getInt("price"), it.getInt("qty")) },
    phone = optString("phone"), email = optString("email"), comment = optString("comment"), source = optString("source"),
    token = optString("token"),
)

private fun JSONObject.toSettings() = Settings(getBoolean("acceptingOrders"), getInt("cooldownMinutes"))
