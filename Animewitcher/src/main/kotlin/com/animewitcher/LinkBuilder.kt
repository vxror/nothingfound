package com.animewitcher

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * [!] XMAX D8/R8 COMPILER WORKAROUND
 * D8 crashes when inline functions with lambdas (like newExtractorLink) 
 * are used directly inside suspend functions (coroutine state machines).
 * This non-inline wrapper isolates the inline bytecode and prevents the crash.
 */
object LinkBuilder {
    fun create(
        source: String,
        name: String,
        url: String,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        quality: Int = Qualities.Unknown.value,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): ExtractorLink {
        return newExtractorLink(source, name, url, type) {
            this.quality = quality
            this.referer = referer ?: ""
            this.headers = headers
        }
    }
}
