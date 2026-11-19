package it.spese.domain.sharing

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Cifratura dei dati condivisi: il servizio online vede solo testo illeggibile; la chiave sta nel codice di abbinamento. AES-256-GCM, con riconoscimento delle manomissioni. */
object ShareCrypto {
    private const val PREFIX = "v1."
    private const val IV_BYTES = 12
    private val random = SecureRandom()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun newKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }
    fun keyToText(key: ByteArray): String = enc.encodeToString(key)
    fun keyFromText(s: String): ByteArray? = runCatching { dec.decode(s.trim()) }.getOrNull()?.takeIf { it.size == 32 }

    fun encrypt(plain: String, key: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        return PREFIX + enc.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** `null` se la chiave è sbagliata, il testo è stato modificato o il formato non è riconosciuto. */
    fun decrypt(token: String, key: ByteArray): String? = runCatching {
        require(token.startsWith(PREFIX))
        val raw = dec.decode(token.removePrefix(PREFIX))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, IV_BYTES))) }
        String(c.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
    }.getOrNull()
}
