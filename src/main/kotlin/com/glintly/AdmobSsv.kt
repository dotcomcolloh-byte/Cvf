package com.glintly

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Verifies AdMob rewarded-ad server-side-verification callbacks (ECDSA / SHA-256). */
object AdmobSsv {
    @Serializable private data class Key(val keyId: Long, val base64: String)
    @Serializable private data class Keys(val keys: List<Key>)

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()
    private val cache = ConcurrentHashMap<Long, PublicKey>()
    @Volatile private var fetchedAt = 0L

    private fun refresh() {
        val res = http.send(
            HttpRequest.newBuilder(URI("https://www.gstatic.com/admob/reward/verifier-keys.json")).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        val kf = KeyFactory.getInstance("EC")
        val fresh = json.decodeFromString<Keys>(res.body()).keys.associate {
            it.keyId to kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it.base64)))
        }
        cache.clear(); cache.putAll(fresh); fetchedAt = now()
    }

    @Synchronized private fun key(id: Long): PublicKey? {
        val age = now() - fetchedAt
        // keys rotate; cache <= 24h. Unknown key id -> refetch at most once a minute (anti-abuse).
        if (age > 24 * 3600_000L || (!cache.containsKey(id) && age > 60_000L)) runCatching { refresh() }
        return cache[id]
    }

    /** @param rawQuery the callback's raw (undecoded) query string, exactly as received. */
    fun verify(rawQuery: String): Boolean {
        val i = rawQuery.indexOf("&signature=")
        if (i < 0) return false
        val content = rawQuery.substring(0, i)                      // everything before &signature=
        val tail = rawQuery.substring(i + 1).split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        val sig = tail["signature"] ?: return false
        val pk = tail["key_id"]?.toLongOrNull()?.let { key(it) } ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pk); update(content.toByteArray(Charsets.UTF_8))
                verify(Base64.getUrlDecoder().decode(sig))
            }
        }.getOrDefault(false)
    }
}
