package com.animewitcher

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking

/**
 * [!] XMAX ULTIMATE SILVER BULLET
 * 1. Direct constructor is blocked by ERROR-level deprecation (cannot be suppressed).
 * 2. Direct newExtractorLink crashes D8 due to inline-lambda state machine collision.
 * 
 * SOLUTION: Use runBlocking to isolate the coroutine state machine, and use the official 
 * newExtractorLink API to guarantee 100% parameter compatibility and bypass the deprecation block.
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
        // runBlocking bridges the synchronous wrapper and the suspend newExtractorLink
        return runBlocking {
            newExtractorLink(source, name, url, type) {
                this.quality = quality
                this.referer = referer ?: ""
                this.headers = headers
            }
        }
    }
}
