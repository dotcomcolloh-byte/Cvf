package com.glintly

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

fun dayStart(): Long = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Transaction.creditedToday(uid: UUID, type: String): Int =
    Ledger.selectAll().where {
        (Ledger.userId eq uid) and (Ledger.type eq type) and (Ledger.createdAt greaterEq dayStart())
    }.count().toInt()

/** Idempotent credit: returns false (and changes nothing) if `ref` was already used. */
fun Transaction.creditUser(uid: UUID, amount: Long, type: String, ref: String): Boolean {
    val ins = Ledger.insertIgnore {
        it[Ledger.userId] = uid; it[Ledger.amount] = amount; it[Ledger.type] = type
        it[Ledger.ref] = ref; it[Ledger.createdAt] = now()
    }
    if (ins.insertedCount == 0) return false
    Users.update({ Users.id eq uid }) {
        it[Users.balance] = Users.balance + amount
        it[Users.totalEarned] = Users.totalEarned + amount
    }
    return true
}

suspend fun allowedUser(uid: UUID): Boolean = dbq {
    Users.selectAll().where { (Users.id eq uid) and (Users.banned eq false) }.count() > 0
}
