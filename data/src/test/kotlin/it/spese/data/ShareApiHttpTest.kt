package it.spese.data

import com.sun.net.httpserver.HttpServer
import it.spese.data.share.ShareApiImpl
import it.spese.domain.sharing.ShareApiException
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Il client parla con il servizio come il Worker si aspetta: percorsi, intestazioni, corpo e gestione degli errori. */
class ShareApiHttpTest {
    private lateinit var server: HttpServer
    private val seen = ArrayList<String>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen.add("${ex.requestMethod} ${ex.requestURI} auth=${ex.requestHeaders.getFirst("Authorization")} key=${ex.requestHeaders.getFirst("X-Create-Key")} body=$body")
            val (code, text) = when {
                ex.requestURI.path == "/v1/shares" && ex.requestHeaders.getFirst("X-Create-Key") != "ok" -> 401 to """{"error":"non autorizzato"}"""
                ex.requestURI.path == "/v1/shares" -> 200 to """{"id":"i","ownerToken":"o","guestToken":"g"}"""
                ex.requestURI.path.endsWith("/out") -> 200 to """{"updated":1700000000000}"""
                ex.requestURI.path.endsWith("/in") -> 200 to """{"items":[{"seq":4,"blob":"b1"},{"seq":5,"blob":"b2"}],"last":5}"""
                else -> 200 to """{"ok":true}"""
            }
            val bytes = text.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }
    @After fun stop() = server.stop(0)

    @Test fun `crea, pubblica, legge la posta e conferma`() = runBlocking {
        val api = ShareApiImpl()
        val c = api.create(base, "ok")
        assertEquals("i", c.id); assertEquals("o", c.ownerToken); assertEquals("g", c.guestToken)
        assertEquals(1700000000000L, api.publish(base, "i", "o", "BLOB"))
        val page = api.inbox(base, "i", "o", 3)
        assertEquals(listOf(4L, 5L), page.items.map { it.seq }); assertEquals(5L, page.last)
        api.ack(base, "i", "o", 5); api.revoke(base, "i", "o")
        assertTrue(seen[0].startsWith("POST /v1/shares ") && seen[0].contains("key=ok"))
        assertTrue(seen[1].startsWith("PUT /v1/shares/i/out") && seen[1].contains("auth=Bearer o") && seen[1].contains("""body={"blob":"BLOB"}"""))
        assertTrue(seen[2].contains("GET /v1/shares/i/in?after=3"))
        assertTrue(seen[3].contains("POST /v1/shares/i/in/ack") && seen[3].contains("""{"upTo":5}"""))
        assertTrue(seen[4].startsWith("DELETE /v1/shares/i"))
    }

    @Test fun `errori del servizio e servizio irraggiungibile diventano messaggi chiari`() = runBlocking {
        val api = ShareApiImpl()
        val e = runCatching { api.create(base, "no") }.exceptionOrNull() as ShareApiException
        assertEquals(401, e.status); assertEquals("non autorizzato", e.message)
        server.stop(0)
        val down = runCatching { api.publish(base, "i", "o", "x") }.exceptionOrNull() as ShareApiException
        assertTrue(down.message!!.contains("non raggiungibile"))
    }
}
