package com.navio.companion

data class ParsedOrder(
    val source: String,
    val kind: String,
    val address: String,
    val raw: String
)

object OrderParser {

    private const val KIND_DROP = "DROP"
    private const val KIND_PICKUP = "PICKUP"
    private const val KIND_UNKNOWN = "UNKNOWN"

    private val DROP_KEYWORDS = listOf(
        "delivering to",
        "deliver to",
        "delivery to",
        "deliver at",
        "drop off at",
        "dropoff at",
        "drop at",
        "drop:",
        "drop "
    )

    private val PICKUP_KEYWORDS = listOf(
        "pickup at",
        "pick up at",
        "pick up from",
        "pickup from",
        "pickup:",
        "collect from",
        "restaurant:",
        "pickup"
    )

    private val PINCODE = Regex("\\b\\d{6}\\b")

    fun parse(source: String, title: String, text: String): ParsedOrder? {
        val full = "$title. $text".replace(Regex("\\s+"), " ").trim()
        if (full.isEmpty()) return null
        val lower = full.lowercase()
        val combined = "$source $lower"
        if (!combined.contains("swiggy") && !combined.contains("zomato")) return null

        for (kw in DROP_KEYWORDS) {
            val idx = lower.indexOf(kw)
            if (idx >= 0) {
                val address = clean(full.substring(idx + kw.length))
                if (address.isNotEmpty()) return ParsedOrder(source, KIND_DROP, address, full)
            }
        }
        for (kw in PICKUP_KEYWORDS) {
            val idx = lower.indexOf(kw)
            if (idx >= 0) {
                val address = clean(full.substring(idx + kw.length))
                if (address.isNotEmpty()) return ParsedOrder(source, KIND_PICKUP, address, full)
            }
        }
        if (PINCODE.containsMatchIn(full) || lower.contains(" near ")) {
            val address = clean(full)
            if (address.isNotEmpty()) return ParsedOrder(source, KIND_UNKNOWN, address, full)
        }
        return null
    }

    private fun clean(s: String): String {
        var t = s.replace(Regex("#\\d+"), " ")
        t = t.replace(Regex("[^\\p{L}\\p{N}\\s,()&/':.-]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = t.trim(' ', ',', '.', ':', '-')
        return t.take(160).trim()
    }
}
