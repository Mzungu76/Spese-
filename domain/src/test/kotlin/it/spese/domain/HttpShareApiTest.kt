package it.spese.domain

import it.spese.domain.sharing.HttpShareApi
import it.spese.domain.sharing.ShareApiException
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Il client parla con il servizio come il Worker si aspetta: percorsi, intestazioni, corpo e gestione degli errori (server HTTP minimo su socket, senza librerie). */
class HttpShareApiTest {
    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private val seen = java.util.Collections.synchronizedList(ArrayList<String>())
    private val base get() = "http://127.0.0.1:${server.localPort}"

    private fun readLine(s: Socket): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = s.getInputStream().read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            out.write(b)
        }
    }

    private fun answer(method: String, target: String, headers: Map<String, String>): Pair<Int, String> {
        val path = target.substringBefore('?')
        return when {
            path == "/v1/shares" && headers["x-create-key"] != "ok" -> 401 to """{"error":"non autorizzato"}"""
            path == "/v1/shares" -> 200 to """{"id":"i","ownerToken":"o","guestToken":"g"}"""
            method == "PUT" && path.endsWith("/out") -> 200 to """{"updated":1700000000000}"""
            path.endsWith("/out") && target.contains("since=7") -> 200 to """{"unchanged":true,"updated":7}"""
            path.endsWith("/out") && path.contains("vuoto") -> 404 to """{"error":"nessun dato"}"""
            path.endsWith("/out") -> 200 to """{"blob":"CIFRATO","updated":9}"""
            method == "POST" && path.endsWith("/in") -> 200 to """{"accepted":2}"""
            path.endsWith("/in") -> 200 to """{"items":[{"seq":4,"blob":"b1"},{"seq":5,"blob":"b2"}],"last":5}"""
            else -> 200 to """{"ok":true}"""
        }
    }

    @Before fun start() {
        server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                try {
                    val first = readLine(sock) ?: continue
                    val (method, target) = first.split(' ').let { p -> p[0] to p[1] }
                    val headers = HashMap<String, String>()
                    while (true) { val l = readLine(sock) ?: break; if (l.isEmpty()) break; headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = String(ByteArray(len).also { buf -> var n = 0; while (n < len) { val r = sock.getInputStream().read(buf, n, len - n); if (r < 0) break; n += r } }, Charsets.UTF_8)
                    seen.add("$method $target auth=${headers["authorization"]} key=${headers["x-create-key"]} body=$body")
                    val (code, text) = answer(method, target, headers)
                    val bytes = text.toByteArray()
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                    }
                } catch (e: Exception) { /* connessione chiusa dal client */ } finally { runCatching { sock.close() } }
            }
        }
    }
    @After fun stop() { runCatching { server.close() } }

    @Test fun `crea, pubblica, legge la posta e conferma`() = runBlocking {
        val api = HttpShareApi()
        val c = api.create(base, "ok")
        assertEquals("i", c.id); assertEquals("o", c.ownerToken); assertEquals("g", c.guestToken)
        assertEquals(1700000000000L, api.publish(base, "i", "o", "BLOB"))
        val page = api.inbox(base, "i", "o", 3)
        assertEquals(listOf(4L, 5L), page.items.map { x -> x.seq }); assertEquals(5L, page.last)
        api.ack(base, "i", "o", 5); api.revoke(base, "i", "o")
        assertTrue(seen[0], seen[0].startsWith("POST /v1/shares ") && seen[0].contains("key=ok"))
        assertTrue(seen[1], seen[1].startsWith("PUT /v1/shares/i/out") && seen[1].contains("auth=Bearer o") && seen[1].contains("""body={"blob":"BLOB"}"""))
        assertTrue(seen[2], seen[2].contains("GET /v1/shares/i/in?after=3"))
        assertTrue(seen[3], seen[3].contains("POST /v1/shares/i/in/ack") && seen[3].contains("""{"upTo":5}"""))
        assertTrue(seen[4], seen[4].startsWith("DELETE /v1/shares/i"))
    }

    @Test fun `l'altra persona legge il riepilogo e manda le spese`() = runBlocking {
        val api = HttpShareApi()
        val f = api.fetch(base, "i", "g", 0)!!
        assertEquals("CIFRATO", f.blob); assertEquals(9L, f.updated); assertFalse(f.unchanged)
        assertTrue(api.fetch(base, "i", "g", 7)!!.unchanged)
        assertNull(api.fetch(base, "vuoto", "g", 0))
        api.send(base, "i", "g", listOf("x1", "x2"))
        assertTrue(seen.last(), seen.last().startsWith("POST /v1/shares/i/in ") && seen.last().contains("auth=Bearer g") && seen.last().contains("{\"items\":[\"x1\",\"x2\"]}"))
    }

    @Test fun `errori del servizio e servizio irraggiungibile diventano messaggi chiari`() = runBlocking {
        val api = HttpShareApi()
        val e = runCatching { api.create(base, "no") }.exceptionOrNull() as ShareApiException
        assertEquals(401, e.status); assertEquals("non autorizzato", e.message)
        server.close()
        val down = runCatching { api.publish(base, "i", "o", "x") }.exceptionOrNull() as ShareApiException
        assertTrue(down.message!!.contains("non raggiungibile"))
    }
}
