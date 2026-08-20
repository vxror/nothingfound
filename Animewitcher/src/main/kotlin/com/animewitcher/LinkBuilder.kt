package com.animewitcher

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

/**
 * [!] XMAX D8/R8 COMPILER WORKAROUND
 * D8 crashes when inline/suspend functions with lambdas (like newExtractorLink) 
 * are used directly inside suspend functions (coroutine state machines).
 * Furthermore, newExtractorLink is a suspend function itself.
 * This non-inline, non-suspend wrapper instantiates the data class directly,
 * completely bypassing both the Kotlin compiler error and the D8 metadata crash.
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
        // Direct instantiation. No suspend. No inline lambdas. Bulletproof.
        return ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = referer ?: "",
            quality = quality,
            isM3u8 = (type == ExtractorLinkType.M3U8),
            headers = headers
        )
    }
}
