package com.animewitcher

import android.util.Base64

object ZenCryptoAndObfuscation {
    private val BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
    private val BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val PACKED_JS_SCRIPT_REGEX = Regex(
        "<script[^>]*>.*?</script>", 
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun findPackedJsInPage(html: String): Triple<String, List<String>, Int>? {
        for (match in PACKED_JS_SCRIPT_REGEX.findAll(html)) {
            val script = match.value
            if (!script.contains("function(p,a,c,k,e,d)") || !script.contains(".split")) continue
            val start = script.indexOf("}("); if (start < 0) continue
            val snippet = script.substring(start)
            val endIdx = snippet.indexOf("'.split"); if (endIdx < 0) continue
            val raw = snippet.substring(2, endIdx + 1)
            val parts = splitPackedJsArgs(raw) ?: continue
            val payloadRaw = parts[0]
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\/", "/")
            val base = parts.getOrNull(1)?.toIntOrNull() ?: 36
            val keywords = parts.getOrNull(3)?.split("|") ?: continue
            return Triple(payloadRaw, keywords, base)
        }
        return null
    }

    fun decodePackedJs(payload: String, keywords: List<String>, base: Int): String {
        val parts = keywords.mapIndexedNotNull { i, kw -> 
            if (kw.isNotBlank()) (Regex.escape(toBase(i, base)) to kw) else null 
        }
        if (parts.isEmpty()) return payload
        val alternation = parts.joinToString("|") { it.first }
        val pattern = Regex("\\b(?:$alternation)\\b")
        val byEncoded = parts.toMap()
        return pattern.replace(payload) { m -> byEncoded[m.value] ?: m.value }
    }

    private fun toBase(n: Int, base: Int): String {
        if (n == 0) return "0"
        val chars = if (base <= 36) BASE36_CHARS else BASE62_CHARS
        val sb = StringBuilder()
        var num = n
        while (num > 0) { 
            sb.append(chars[num % base])
            num /= base 
        }
        return sb.reverse().toString()
    }

    private fun splitPackedJsArgs(s: String): List<String>? {
        val args = mutableListOf<String>()
        var i = 0
        while (i < s.length && args.size < 4) {
            if (s[i] == '\'') {
                var end = i + 1
                while (end < s.length) {
                    end = s.indexOf('\'', end)
                    if (end < 0) return null
                    var slashCount = 0
                    var ci = end - 1
                    while (ci >= 0 && s[ci] == '\\') { 
                        slashCount++
                        ci-- 
                    }
                    if (slashCount % 2 == 0) { 
                        args.add(s.substring(i + 1, end))
                        i = end + 1
                        break 
                    }
                    end++
                }
            } else if (s[i] == ',' || s[i] == ' ') { 
                i++ 
            } else {
                val end = s.indexOfAny(charArrayOf(',', ')', ' '), i)
                    .let { if (it < 0) s.length else it }
                args.add(s.substring(i, end))
                i = end
            }
        }
        return if (args.size >= 4) args else null
    }

    fun sigDecode(url: String): String {
        if (url.isBlank()) return url
        val sig = url.split("sig=").getOrNull(1)?.split("&")?.getOrNull(0) ?: return url
        val t = sig.chunked(2).joinToString("") { 
            (it.toInt(16) xor 2).toChar().toString() 
        }.let {
            val padding = when (it.length % 4) { 
                2 -> "=="
                3 -> "="
                else -> "" 
            }
            String(Base64.decode((it + padding).toByteArray(), Base64.DEFAULT))
        }.dropLast(5).reversed().toCharArray().apply {
            for (i in indices step 2) { 
                if (i + 1 < size) { 
                    this[i] = this[i + 1].also { this[i + 1] = this[i] } 
                } 
            }
        }.concatToString().dropLast(5)
        return url.replace(sig, t)
    }
}
