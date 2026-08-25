package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class MegaExtractor : ExtractorApi() {
    override val name = "Mega"
    override val mainUrl = "https://mega.nz"
    override val requiresReferer = false

    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val quality = labelQuality(linkLabel)
        val suffix = linkLabel?.let { " $it" } ?: ""

        // OPTION 1: LOCAL PROXY (Primary - Fast, AES Decryption, Seeking)
        try {
            val localUrl = MegaProxy.resolve(url)
            if (localUrl != null) {
                callback(newExtractorLink(name, "Mega$suffix", localUrl, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                })
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega local proxy error: ${e.message}")
        }

        // OPTION 2: CLOUDFLARE WORKER BYPASS (Use if Local hits 509 IP Limit)
        try {
            val standardBase64 = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val cfWorkerLink = "https://mega.wldbs.workers.dev/download?url=$standardBase64"
            
            callback(newExtractorLink(name, "Mega (CF Bypass)$suffix", cfWorkerLink, ExtractorLinkType.VIDEO) {
                this.quality = quality
            })
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega CF worker link error: ${e.message}")
        }
    }
}
