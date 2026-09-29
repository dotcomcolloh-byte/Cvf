package com.glintly

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    initDb()
    install(ContentNegotiation) { json() }
    install(XForwardedHeaders)           // correct client IP behind nginx/Caddy/Cloud proxy
    install(CallLogging)
    install(RateLimit) { register(RateLimitName("auth")) { rateLimiter(limit = 20, refillPeriod = 1.minutes) } }
    install(StatusPages) {
        exception<ApiError> { call, e -> call.respond(e.status, Msg(e.code)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, Msg("bad_request")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("unhandled", e)
            call.respond(HttpStatusCode.InternalServerError, Msg("server_error"))
        }
    }
    install(Authentication) {
        jwt("user") {
            verifier(JWT.require(Algorithm.HMAC256(Cfg.jwtSecret)).withIssuer(JWT_ISS).build())
            validate { c ->
                val id = c.payload.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (id != null && allowedUser(id)) JWTPrincipal(c.payload) else null
            }
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
        appRoutes()
    }
}
