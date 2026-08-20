package com.animewitcher

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

object LinkBuilder {
    fun create(
        source: String, name: String, url: String,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        quality: Int = Qualities.Unknown.value,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): ExtractorLink {
        // Direct inline call. No runBlocking. No suspend context. Bulletproof.
        return newExtractorLink(source, name, url, type) {
            this.quality = quality
            this.referer = referer ?: ""
            this.headers = headers
        }
    }
}
