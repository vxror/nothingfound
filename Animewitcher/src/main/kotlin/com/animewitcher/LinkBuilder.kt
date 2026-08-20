package com.animewitcher

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

/**
 * [!] XMAX ULTIMATE D8/R8 & KOTLIN COMPILER WORKAROUND
 * 1. Kotlin blocks the constructor because it's deprecated.
 * 2. D8 blocks `newExtractorLink` because the inline lambda crashes the dexer.
 * Solution: Suppress the Kotlin deprecation error and use the constructor directly.
 */
object LinkBuilder {
    
    // 🚀 THE SILVER BULLET: Forces Kotlin to ignore the deprecation error
    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
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
