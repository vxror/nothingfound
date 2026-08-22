package com.witanime

internal const val EXTRACTOR_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal fun hostOf(url: String): String = try {
    val u = java.net.URI(url); "${u.scheme}://${u.host}"
} catch (e: Exception) { url }

internal fun linkHost(link: String): String = try {
    java.net.URI(link).host?.lowercase() ?: ""
} catch (e: Exception) { "" }

/** base encoder supporting radix up to 62 (Int.toString(radix) maxes at 36 and CRASHES on packer radix 62) */
internal fun encodeBase(v: Int, radix: Int): String {
    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    if (v == 0) return "0"
    var n = v; val sb = StringBuilder()
    while (n > 0) { sb.insert(0, chars[n % radix]); n /= radix }
    return sb.toString()
}

/** p,a,c,k,e,d unpacker — radix-safe */
internal fun unpackPackedJs(html: String): String? {
    val m = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    ).find(html) ?: return null
    val p = m.groupValues[1]
    val a = m.groupValues[2].toIntOrNull() ?: return null
    val c = m.groupValues[3].toIntOrNull() ?: return null
    if (a < 2 || a > 62) return null
    val k = m.groupValues[4].split("|")
    val dict = mutableMapOf<String, String>()
    var count = c
    while (count-- > 0) {
        val key = encodeBase(count, a)
        dict[key] = if (count < k.size && k[count].isNotEmpty()) k[count] else key
    }
    return Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value }
}

/** dood rotates domains constantly — pattern match, never list */
internal fun isDoodLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return h.contains("dood") || h.contains("d000d") || h.contains("ds2play") ||
            h.contains("dstore") || h.contains("dstream")
}

/** streamwish family wildcard */
internal fun isStreamWishLink(link: String): Boolean {
    val h = linkHost(link)
    return h.isNotBlank() && (h.contains("wish") || h.contains("swhoi"))
}

internal fun isMegaLink(link: String): Boolean {
    val h = linkHost(link)
    return h.contains("mega.nz") || h.contains("mega.co.nz")
}
