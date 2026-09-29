package com.glintly

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object Users : Table("users") {
    val id = uuid("id")
    val sub = varchar("sub", 64).uniqueIndex()
    val email = varchar("email", 256)
    val name = varchar("name", 256)
    val picture = varchar("picture", 512).nullable()
    val balance = long("balance").default(0)
    val totalEarned = long("total_earned").default(0)
    val totalWithdrawn = long("total_withdrawn").default(0)
    val banned = bool("banned").default(false)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Append-only. `ref` is UNIQUE, which makes every credit idempotent (replay-proof). */
object Ledger : Table("ledger") {
    val id = long("id").autoIncrement()
    val userId = uuid("user_id").index()
    val amount = long("amount")
    val type = varchar("type", 16)            // AD | TASK | WITHDRAW | REFUND
    val ref = varchar("ref", 160).uniqueIndex()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AdSessions : Table("ad_sessions") {
    val id = uuid("id")                        // sent to AdMob as custom_data
    val userId = uuid("user_id").index()
    val status = varchar("status", 12)         // PENDING | REWARDED
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object VisitTasks : Table("visit_tasks") {
    val id = integer("id").autoIncrement()
    val title = varchar("title", 200)
    val url = varchar("url", 1024)
    val reward = long("reward")
    val dwellSec = integer("dwell_sec").default(15)
    val active = bool("active").default(true)
    override val primaryKey = PrimaryKey(id)
}

object TaskClaims : Table("task_claims") {
    val token = uuid("token")
    val userId = uuid("user_id")
    val taskId = integer("task_id")
    val createdAt = long("created_at")
    val clickedAt = long("clicked_at").nullable()   // set ONLY by our /r/{token} redirect
    val claimedAt = long("claimed_at").nullable()
    override val primaryKey = PrimaryKey(token)
    init { uniqueIndex(userId, taskId) }
}

object Withdrawals : Table("withdrawals") {
    val id = long("id").autoIncrement()
    val userId = uuid("user_id").index()
    val paypalEmail = varchar("paypal_email", 256)
    val credits = long("credits")
    val usdCents = long("usd_cents")
    val status = varchar("status", 12)         // PENDING | PAID | REJECTED
    val createdAt = long("created_at")
    val processedAt = long("processed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

fun now() = System.currentTimeMillis()

fun initDb() {
    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl = Cfg.dbUrl; username = Cfg.dbUser; password = Cfg.dbPass; maximumPoolSize = 10
    })
    Database.connect(ds)
    transaction {
        SchemaUtils.createMissingTablesAndColumns(Users, Ledger, AdSessions, VisitTasks, TaskClaims, Withdrawals)
    }
}

suspend fun <T> dbq(block: suspend Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }
