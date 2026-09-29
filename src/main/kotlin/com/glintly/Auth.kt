package com.glintly

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

const val JWT_ISS = "glintly"

fun makeJwt(uid: UUID): String = JWT.create()
    .withIssuer(JWT_ISS).withSubject(uid.toString())
    .withExpiresAt(Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000))
    .sign(Algorithm.HMAC256(Cfg.jwtSecret))

private val googleVerifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
    .setAudience(listOf(Cfg.googleClientId)).build()

/** Verifies signature, issuer, audience and expiry of a Google ID token. Null = invalid. */
suspend fun verifyGoogle(idToken: String): GoogleIdToken.Payload? = withContext(Dispatchers.IO) {
    runCatching { googleVerifier.verify(idToken)?.payload }.getOrNull()
}
