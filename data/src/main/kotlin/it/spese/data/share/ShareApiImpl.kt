package it.spese.data.share

import it.spese.domain.sharing.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Client HTTP del servizio di condivisione. Tutto ciò che si invia è già cifrato sul telefono. */
@Singleton
class ShareApiImpl @Inject constructor() : ShareApi {
    private suspend fun call(method: String, url: String, token: String? = null, createKey: String? = null, body: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method; conn.connectTimeout = 15_000; conn.readTimeout = 25_000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (createKey != null) conn.setRequestProperty("X-Create-Key", createKey)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { r -> r.readText() }.orEmpty()
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (code !in 200..299) throw ShareApiException(obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "Errore del servizio ($code)", code)
            obj ?: throw ShareApiException("Risposta non valida dal servizio", code)
        } catch (e: ShareApiException) { throw e }
        catch (e: IOException) { throw ShareApiException("Servizio non raggiungibile: controlla la connessione") }
        finally { conn.disconnect() }
    }

    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

    override suspend fun create(baseUrl: String, createKey: String): CreatedShare {
        val o = call("POST", "$baseUrl/v1/shares", createKey = createKey)
        return CreatedShare(o.text("id") ?: bad(), o.text("ownerToken") ?: bad(), o.text("guestToken") ?: bad())
    }

    override suspend fun publish(baseUrl: String, id: String, token: String, blob: String): Long =
        call("PUT", "$baseUrl/v1/shares/$id/out", token, body = buildJsonObject { put("blob", blob) }.toString()).num("updated") ?: 0L

    override suspend fun inbox(baseUrl: String, id: String, token: String, after: Long): InboxPage {
        val o = call("GET", "$baseUrl/v1/shares/$id/in?after=$after&limit=100", token)
        val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            InboxItem(x.num("seq") ?: return@mapNotNull null, x.text("blob") ?: return@mapNotNull null)
        }
        return InboxPage(items, o.num("last") ?: after)
    }

    override suspend fun ack(baseUrl: String, id: String, token: String, upTo: Long) {
        call("POST", "$baseUrl/v1/shares/$id/in/ack", token, body = buildJsonObject { put("upTo", upTo) }.toString())
    }

    override suspend fun revoke(baseUrl: String, id: String, token: String) { call("DELETE", "$baseUrl/v1/shares/$id", token) }

    private fun bad(): Nothing = throw ShareApiException("Risposta non valida dal servizio")
}
