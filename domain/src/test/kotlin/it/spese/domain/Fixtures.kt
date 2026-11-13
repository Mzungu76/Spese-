package it.spese.domain

import it.spese.domain.backup.BackupEnvelope
import it.spese.domain.backup.DomainSnapshot
import it.spese.domain.backup.LegacyBackupCodec
import it.spese.domain.backup.LegacyMapper
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

object Fixtures {
    fun bytes(name: String): ByteArray = Fixtures::class.java.getResourceAsStream("/$name")!!.readBytes()
    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)
    val golden: JsonObject by lazy { Json.parseToJsonElement(text("golden.json")).jsonObject }
    val backupA: BackupEnvelope by lazy { LegacyBackupCodec.parse(text("backupA.json")) }
    val backupB: BackupEnvelope by lazy { LegacyBackupCodec.parse(text("backupB.json")) }
    val snapshotA: DomainSnapshot by lazy { LegacyMapper.toDomain(backupA.state) }
}

fun JsonElement.d(): BigDecimal = BigDecimal(jsonPrimitive.content)
fun JsonElement.s(): String = jsonPrimitive.content
fun assertMoney(msg: String, expected: BigDecimal, actual: BigDecimal, tol: String = "0.011") {
    assertTrue("$msg: atteso $expected, ottenuto $actual", (expected - actual).abs() <= BigDecimal(tol))
}
