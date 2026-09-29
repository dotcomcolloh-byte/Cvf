package com.glintly

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import java.util.UUID

private fun ApplicationCall.uid(): UUID =
    UUID.fromString(principal<JWTPrincipal>()!!.payload.subject)

private fun ResultRow.toUserDto() = UserDto(
    id = this[Users.id].toString(), email = this[Users.email], name = this[Users.name],
    picture = this[Users.picture], balance = this[Users.balance],
    totalEarned = this[Users.totalEarned], totalWithdrawn = this[Users.totalWithdrawn],
)

private fun requireAdmin(call: ApplicationCall) {
    if (call.request.header("X-Admin-Key") != Cfg.adminKey) throw ApiError(HttpStatusCode.Unauthorized, "admin_key_required")
}

/** Atomic, race-safe debit: fails clean (returns false) rather than ever letting balance go negative. */
private fun Transaction.debitUser(uid: UUID, amount: Long, type: String, ref: String): Boolean {
    val ins = Ledger.insertIgnore {
        it[Ledger.userId] = uid; it[Ledger.amount] = -amount; it[Ledger.type] = type
        it[Ledger.ref] = ref; it[Ledger.createdAt] = now()
    }
    if (ins.insertedCount == 0) return false
    val updated = Users.update({ (Users.id eq uid) and (Users.balance greaterEq amount) }) {
        it[Users.balance] = Users.balance - amount
        it[Users.totalWithdrawn] = Users.totalWithdrawn + amount
    }
    if (updated == 0) { Ledger.deleteWhere { Ledger.ref eq ref }; return false }
    return true
}

fun Routing.appRoutes() {

    // ---------- Auth ----------
    rateLimit(RateLimitName("auth")) { post("/auth/google") {
        val req = call.receive<GoogleAuthRequest>()
        val payload = verifyGoogle(req.idToken) ?: throw ApiError(HttpStatusCode.Unauthorized, "invalid_google_token")
        val sub = payload.subject
        val email = payload.email ?: ""
        val name = (payload["name"] as? String) ?: email
        val picture = payload["picture"] as? String

        val (uid, dto) = dbq {
            val existing = Users.selectAll().where { Users.sub eq sub }.singleOrNull()
            val id = existing?.get(Users.id) ?: UUID.randomUUID().also { newId ->
                Users.insert {
                    it[Users.id] = newId; it[Users.sub] = sub; it[Users.email] = email
                    it[Users.name] = name; it[Users.picture] = picture; it[Users.createdAt] = now()
                }
            }
            if (existing != null) Users.update({ Users.id eq id }) {
                it[Users.email] = email; it[Users.name] = name; it[Users.picture] = picture
            }
            id to Users.selectAll().where { Users.id eq id }.single().toUserDto()
        }
        call.respond(AuthResponse(makeJwt(uid), dto))
    } }

    // ---------- AdMob server-side verification (called by Google, not the app) ----------
    get("/admob/ssv") {
        val raw = call.request.queryString()
        if (AdmobSsv.verify(raw)) {
            val sessionId = call.request.queryParameters["custom_data"]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (sessionId != null) dbq {
                val session = AdSessions.selectAll().where { AdSessions.id eq sessionId }.singleOrNull()
                if (session != null && session[AdSessions.status] == "PENDING") {
                    val uid = session[AdSessions.userId]
                    if (creditUser(uid, Cfg.rewardPerAd, "AD", "ad:$sessionId")) {
                        AdSessions.update({ AdSessions.id eq sessionId }) { it[status] = "REWARDED" }
                    }
                }
            }
        }
        // Always 200 so Google doesn't retry-storm us; invalid/unverified callbacks are simply ignored.
        call.respond(HttpStatusCode.OK)
    }

    // ---------- Visit-site click redirect (opened in a real browser, proves a real navigation) ----------
    get("/r/{token}") {
        val token = call.parameters["token"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val url = dbq {
            val claim = TaskClaims.selectAll().where { TaskClaims.token eq token }.singleOrNull() ?: return@dbq null
            if (claim[TaskClaims.clickedAt] == null) {
                TaskClaims.update({ TaskClaims.token eq token }) { it[clickedAt] = now() }
            }
            VisitTasks.selectAll().where { VisitTasks.id eq claim[TaskClaims.taskId] }.singleOrNull()?.get(VisitTasks.url)
        }
        if (url == null) call.respond(HttpStatusCode.NotFound) else call.respondRedirect(url, permanent = false)
    }

    authenticate("user") {
        // ---------- Wallet / profile ----------
        get("/wallet") {
            val uid = call.uid()
            val result = dbq {
                val user = Users.selectAll().where { Users.id eq uid }.single().toUserDto()
                val ledger = Ledger.selectAll().where { Ledger.userId eq uid }
                    .orderBy(Ledger.createdAt, SortOrder.DESC).limit(50)
                    .map { LedgerEntryDto(it[Ledger.amount], it[Ledger.type], it[Ledger.createdAt]) }
                WalletResponse(user, ledger)
            }
            call.respond(result)
        }

        // ---------- Rewarded ads ----------
        post("/ads/session") {
            val uid = call.uid()
            val sessionId = dbq {
                if (adSessionsToday(uid) >= Cfg.maxAdsPerDay) throw ApiError(HttpStatusCode.TooManyRequests, "daily_ad_limit")
                val last = lastAdSessionAt(uid)
                if (last != null && now() - last < Cfg.adCooldownSec * 1000) throw ApiError(HttpStatusCode.TooManyRequests, "cooldown")
                val id = UUID.randomUUID()
                AdSessions.insert {
                    it[AdSessions.id] = id; it[AdSessions.userId] = uid
                    it[AdSessions.status] = "PENDING"; it[AdSessions.createdAt] = now()
                }
                id
            }
            call.respond(AdSessionResponse(sessionId.toString(), Cfg.admobUnit))
        }

        // ---------- Visit-site tasks ----------
        get("/tasks") {
            val uid = call.uid()
            val list = dbq {
                val claimed = TaskClaims.selectAll().where { TaskClaims.userId eq uid }.map { it[TaskClaims.taskId] }.toSet()
                VisitTasks.selectAll().where { VisitTasks.active eq true }.mapNotNull { row ->
                    val id = row[VisitTasks.id]
                    if (id in claimed) null
                    else TaskDto(id, row[VisitTasks.title], row[VisitTasks.url], row[VisitTasks.reward], row[VisitTasks.dwellSec])
                }
            }
            call.respond(list)
        }

        post("/tasks/{id}/start") {
            val uid = call.uid()
            val taskId = call.parameters["id"]?.toIntOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "bad_task")
            val token = dbq {
                VisitTasks.selectAll().where { (VisitTasks.id eq taskId) and (VisitTasks.active eq true) }.singleOrNull()
                    ?: throw ApiError(HttpStatusCode.NotFound, "task_not_found")
                val already = TaskClaims.selectAll().where { (TaskClaims.userId eq uid) and (TaskClaims.taskId eq taskId) }.singleOrNull()
                if (already != null) throw ApiError(HttpStatusCode.Conflict, "already_started")
                if (tasksClaimedToday(uid) >= Cfg.maxTasksPerDay) throw ApiError(HttpStatusCode.TooManyRequests, "daily_task_limit")
                val tok = UUID.randomUUID()
                TaskClaims.insert {
                    it[TaskClaims.token] = tok; it[TaskClaims.userId] = uid; it[TaskClaims.taskId] = taskId
                    it[TaskClaims.createdAt] = now()
                }
                tok
            }
            call.respond(TaskStartResponse("${Cfg.baseUrl}/r/$token", token.toString()))
        }

        post("/tasks/claim/{token}") {
            val uid = call.uid()
            val token = call.parameters["token"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ApiError(HttpStatusCode.BadRequest, "bad_token")
            dbq {
                val claim = TaskClaims.selectAll().where { TaskClaims.token eq token }.singleOrNull()
                    ?: throw ApiError(HttpStatusCode.NotFound, "not_found")
                if (claim[TaskClaims.userId] != uid) throw ApiError(HttpStatusCode.Forbidden, "not_yours")
                if (claim[TaskClaims.claimedAt] != null) throw ApiError(HttpStatusCode.Conflict, "already_claimed")
                val clickedAt = claim[TaskClaims.clickedAt] ?: throw ApiError(HttpStatusCode.BadRequest, "not_clicked_yet")
                val task = VisitTasks.selectAll().where { VisitTasks.id eq claim[TaskClaims.taskId] }.single()
                if (now() - clickedAt < task[VisitTasks.dwellSec] * 1000L) throw ApiError(HttpStatusCode.BadRequest, "too_soon")
                if (creditUser(uid, task[VisitTasks.reward], "TASK", "task:$token")) {
                    TaskClaims.update({ TaskClaims.token eq token }) { it[claimedAt] = now() }
                }
            }
            call.respond(HttpStatusCode.OK)
        }

        // ---------- Withdrawals ----------
        post("/withdrawals") {
            val uid = call.uid()
            val req = call.receive<WithdrawRequest>()
            if (req.credits < Cfg.minWithdraw) throw ApiError(HttpStatusCode.BadRequest, "below_minimum")
            dbq {
                val ref = "wd:${UUID.randomUUID()}"
                if (!debitUser(uid, req.credits, "WITHDRAW", ref)) throw ApiError(HttpStatusCode.BadRequest, "insufficient_balance")
                Withdrawals.insert {
                    it[Withdrawals.userId] = uid; it[Withdrawals.paypalEmail] = req.paypalEmail
                    it[Withdrawals.credits] = req.credits; it[Withdrawals.usdCents] = req.credits * 100 / Cfg.creditsPerUsd
                    it[Withdrawals.status] = "PENDING"; it[Withdrawals.createdAt] = now()
                }
            }
            call.respond(HttpStatusCode.Created)
        }
    }

    // ---------- Admin (header X-Admin-Key, not a user JWT) ----------
    route("/admin") {
        post("/tasks") {
            requireAdmin(call)
            val req = call.receive<CreateTaskRequest>()
            dbq {
                VisitTasks.insert {
                    it[title] = req.title; it[url] = req.url; it[reward] = req.reward
                    it[dwellSec] = req.dwellSec; it[active] = true
                }
            }
            call.respond(HttpStatusCode.Created)
        }

        get("/withdrawals") {
            requireAdmin(call)
            val status = call.request.queryParameters["status"] ?: "PENDING"
            val list = dbq {
                Withdrawals.selectAll().where { Withdrawals.status eq status }
                    .orderBy(Withdrawals.createdAt, SortOrder.ASC)
                    .map { WithdrawalDto(it[Withdrawals.id], it[Withdrawals.userId].toString(), it[Withdrawals.paypalEmail],
                        it[Withdrawals.credits], it[Withdrawals.usdCents], it[Withdrawals.status], it[Withdrawals.createdAt]) }
            }
            call.respond(list)
        }

        post("/withdrawals/{id}/approve") {
            requireAdmin(call)
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "bad_id")
            dbq {
                val updated = Withdrawals.update({ (Withdrawals.id eq id) and (Withdrawals.status eq "PENDING") }) {
                    it[status] = "PAID"; it[processedAt] = now()
                }
                if (updated == 0) throw ApiError(HttpStatusCode.Conflict, "not_pending")
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/withdrawals/{id}/reject") {
            requireAdmin(call)
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "bad_id")
            dbq {
                val row = Withdrawals.selectAll().where { (Withdrawals.id eq id) and (Withdrawals.status eq "PENDING") }.singleOrNull()
                    ?: throw ApiError(HttpStatusCode.Conflict, "not_pending")
                creditUser(row[Withdrawals.userId], row[Withdrawals.credits], "REFUND", "refund:$id")
                Withdrawals.update({ Withdrawals.id eq id }) { it[status] = "REJECTED"; it[processedAt] = now() }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}
