package com.promulticam.app

/* a list of messages that asks the server only for new ones (much cheaper); every few minutes it reloads everything once (to catch deleted ones) */
class Feed(private val col: String, private val parent: String = "", private val newestFirstLimit: Int = 150) {
    val docs = ArrayList<Fs.Doc>(); private var last = 0L; private var n = 0
    /* returns true when the list changed. Call off the main thread. */
    fun refresh(): Boolean {
        val full = docs.isEmpty() || n++ % 30 == 0
        val got = if (full) Fs.list(col, "at", desc = true, limit = newestFirstLimit, parent = parent).reversed()
            else Fs.list(col, "at", desc = false, limit = 100, parent = parent, afterMs = last)
        if (full) { val same = got.map { it.id } == docs.map { it.id }; docs.clear(); docs.addAll(got); last = docs.maxOfOrNull { it.t("at") } ?: 0L; return !same }
        val fresh = got.filter { g -> docs.none { it.id == g.id } }; if (fresh.isEmpty()) return false
        docs.addAll(fresh); last = maxOf(last, fresh.maxOf { it.t("at") }); return true
    }
    fun drop(id: String) { docs.removeAll { it.id == id } }
}
