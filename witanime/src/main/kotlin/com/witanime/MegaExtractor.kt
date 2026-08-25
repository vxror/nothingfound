package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class MegaExtractor : ExtractorApi() {
    override val name = "Mega"
    override val mainUrl = "https://mega.nz"
    override val requiresReferer = false

    /** quality label from the parent server (HD/FHD), set by routeLink */
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val quality = labelQuality(linkLabel)
        val suffix = linkLabel?.let { " $it" } ?: ""

        // OPTION 1: LOCAL PROXY (Primary)
        // Fast, handles AES-CTR decryption locally on the device, supports perfect video seeking.
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

        // OPTION 2: CLOUDFLARE WORKER BYPASS (Backup)
        // Bypasses IP and Uploader 509 limits by routing through Cloudflare's edge servers.
        try {
            // [!] FIX: The Cloudflare worker strictly requires "/file/" URLs, not "/embed/" URLs.
            // We rewrite the path before encoding it to ensure the worker accepts the link.
            val fixedUrl = url.replace("/embed/", "/file/")
            
            // Base64.NO_WRAP perfectly replicates JavaScript's btoa() without adding line breaks
            val standardBase64 = Base64.encodeToString(fixedUrl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val cfWorkerLink = "https://mega.wldbs.workers.dev/download?url=$standardBase64"
            
            callback(newExtractorLink(name, "Mega (CF Bypass)$suffix", cfWorkerLink, ExtractorLinkType.VIDEO) {
                this.quality = quality
            })
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega CF worker link error: ${e.message}")
        }
    }
}
