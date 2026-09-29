package com.glintly

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** Thrown anywhere in a route to short-circuit with a clean JSON error. */
class ApiError(val status: HttpStatusCode, val code: String) : RuntimeException(code)

@Serializable
data class Msg(val error: String)

@Serializable
data class GoogleAuthRequest(val idToken: String)

@Serializable
data class AuthResponse(val token: String, val user: UserDto)

@Serializable
data class UserDto(
    val id: String, val email: String, val name: String, val picture: String?,
    val balance: Long, val totalEarned: Long, val totalWithdrawn: Long,
)

@Serializable
data class WalletResponse(val user: UserDto, val ledger: List<LedgerEntryDto>)

@Serializable
data class LedgerEntryDto(val amount: Long, val type: String, val createdAt: Long)

@Serializable
data class AdSessionResponse(val sessionId: String, val adUnitId: String)

@Serializable
data class TaskDto(val id: Int, val title: String, val url: String, val reward: Long, val dwellSec: Int)

@Serializable
data class TaskStartResponse(val redirectUrl: String, val token: String)

@Serializable
data class WithdrawRequest(val paypalEmail: String, val credits: Long)

@Serializable
data class CreateTaskRequest(val title: String, val url: String, val reward: Long, val dwellSec: Int = 15)

@Serializable
data class WithdrawalDto(
    val id: Long, val userId: String, val paypalEmail: String,
    val credits: Long, val usdCents: Long, val status: String, val createdAt: Long,
)
