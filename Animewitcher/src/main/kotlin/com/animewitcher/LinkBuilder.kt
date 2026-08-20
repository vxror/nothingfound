package com.animewitcher

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

object LinkBuilder {
    // MUST be suspend to call newExtractorLink
    suspend fun create(
        source: String,
        name: String,
        url: String,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        quality: Int = Qualities.Unknown.value,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): ExtractorLink {
        // [!] THE SILVER BULLET: NO LAMBDA PASSED.
        // This prevents the inline lambda from being captured inside the suspend state machine,
        // which is the exact trigger for the D8/R8 "Should never be called" metadata crash.
        val link = newExtractorLink(source, name, url, type)
        
        // Mutate properties directly. They are public 'var' in Cloudstream3.
        link.quality = quality
        link.referer = referer ?: ""
        link.headers = headers
        
        return link
    }
}
