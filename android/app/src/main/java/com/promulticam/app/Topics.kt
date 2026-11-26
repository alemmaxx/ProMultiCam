package com.promulticam.app

/* kinds of support issue (same keys as the web) */
object Topics {
    val ALL = listOf("pay", "account", "connect", "record", "editor", "idea", "other")
    fun name(k: String) = when (k) { "pay" -> Ui.t("Payment & plan", "Bayaran & pelan"); "account" -> Ui.t("Account & login", "Akaun & log masuk"); "connect" -> Ui.t("Camera connection", "Sambungan kamera")
        "record" -> Ui.t("Recording & video", "Rakaman & video"); "editor" -> "Editor"; "idea" -> Ui.t("Suggestion", "Cadangan"); "other" -> Ui.t("Other", "Lain-lain"); else -> k }
}
