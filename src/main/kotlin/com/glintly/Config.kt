package com.glintly

object Cfg {
    private fun env(k: String, d: String? = null): String = System.getenv(k) ?: d ?: error("Missing env var $k")
    val dbUrl = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/glintly")
    val dbUser = env("DB_USER", "glintly")
    val dbPass = env("DB_PASS", "glintly")
    val jwtSecret = env("JWT_SECRET")
    val googleClientId = env("GOOGLE_WEB_CLIENT_ID")
    val adminKey = env("ADMIN_KEY")
    val admobUnit = env("ADMOB_REWARDED_UNIT_ID")
    val baseUrl = env("PUBLIC_BASE_URL").trimEnd('/')
    val rewardPerAd = env("REWARD_PER_AD", "5").toLong()
    val maxAdsPerDay = env("MAX_ADS_PER_DAY", "30").toInt()
    val adCooldownSec = env("AD_COOLDOWN_SEC", "30").toLong()
    val maxTasksPerDay = env("MAX_TASKS_PER_DAY", "10").toInt()
    val creditsPerUsd = env("CREDITS_PER_USD", "1000").toLong()
    val minWithdraw = env("MIN_WITHDRAW_CREDITS", "5000").toLong()
}
