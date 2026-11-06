package com.glamsolo.yetipizza

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import kotlin.test.assertFailsWith

/** The client against canned server answers, and (when PIZZA_SERVER_URL is set) against a real jeti-pizza/server. */
@RunWith(AndroidJUnit4::class)
class ApiTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = mutableListOf<String>()

    private fun respond(status: Int, body: String) {
        server.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI} ${ex.requestHeaders.getFirst("X-HTTP-Method-Override") ?: ""} ${ex.requestBody.readBytes().decodeToString()}"
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private val api get() = Api("http://127.0.0.1:${server.address.port}", token = "t0ken")

    @After fun stop() = server.stop(0)

    @Test fun `cooldown answer keeps the wait time`() {
        respond(429, """{"error":"Новый можно оформить через 15 мин.","retryAfter":900}""")
        val e = assertFailsWith<ApiException> { api.createOrder("Анна", "+79271112233", "a@b.ru", "asap", "", mapOf("1" to 2)) }
        assertEquals(429, e.status)
        assertEquals(900, e.retryAfterSeconds)
        assertTrue(requests.single().contains(""""source":"app""""))
        assertTrue(requests.single().contains(""""items":[{"id":"1","qty":2}]"""))
    }

    @Test fun `PATCH is tunnelled through POST`() {
        respond(200, """{"id":"9","category":"pizza","name":"Пепперони","description":"","unit":"330 г","price":350,"available":false}""")
        val item = api.updateItem("9", available = false)
        assertEquals(false, item.available)
        assertTrue(requests.single().startsWith("POST /api/admin/items/9 PATCH"))
    }

    @Test fun `typed server address gets a scheme and loses the trailing slash`() {
        assertEquals("http://192.168.1.5:8080", serverUrl(" 192.168.1.5:8080/ "))
        assertEquals("https://pizza.example.ru", serverUrl("https://pizza.example.ru/"))
    }

    @Test fun `no server means a readable error`() {
        val e = assertFailsWith<ApiException> { Api("http://127.0.0.1:1").menu() }
        assertEquals(0, e.status)
    }

    @Test fun `real server round trip`() {
        val url = System.getenv("PIZZA_SERVER_URL")
        val password = System.getenv("PIZZA_ADMIN_PASSWORD")
        assumeTrue("set PIZZA_SERVER_URL and PIZZA_ADMIN_PASSWORD to run against a live server", url != null && password != null)
        server.start()
        val shop = Api(url!!)
        val menu = shop.menu()
        assertTrue(menu.items.isNotEmpty())
        val item = menu.items.first { it.available }
        val phone = "+7927" + (1_000_000..9_999_999).random()
        val order = shop.createOrder("Тест", phone, "$phone@test.ru", menu.info.slots.firstOrNull() ?: "asap", "из ApiTest", mapOf(item.id to 2))
        assertEquals(item.price * 2, order.total)
        assertEquals("new", shop.order(order.id, order.token).status)
        assertEquals(429, assertFailsWith<ApiException> { shop.createOrder("Тест", phone, "x$phone@test.ru", "asap", "", mapOf(item.id to 1)) }.status)

        val staff = Api(url, shop.login("admin", password!!))
        assertTrue(staff.orders().any { it.id == order.id && it.phone == phone })
        assertEquals("cancelled", staff.setStatus(order.id, "cancelled").status)
        assertEquals(item.price, staff.updateItem(item.id, price = item.price).price)
        assertTrue(staff.report(java.time.LocalDate.now(com.glamsolo.yetipizza.ui.Moscow).toString(), java.time.LocalDate.now(com.glamsolo.yetipizza.ui.Moscow).toString()).cancelled >= 1)
        staff.logout()
        assertEquals(401, assertFailsWith<ApiException> { staff.orders() }.status)
    }
}
