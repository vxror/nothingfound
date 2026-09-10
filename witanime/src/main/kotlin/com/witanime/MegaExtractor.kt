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
        try {
            val local = MegaProxy.resolve(url)
            if (local != null) {
                // local proxy — walks the sid bucket pool with failover
                callback(newExtractorLink(name, "Mega", local, ExtractorLinkType.VIDEO) {
                    quality = labelQuality(linkLabel)
                })
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega local proxy error: ${e.message}")
        }

        try {
            // CF worker — the independent fallback bucket (different IP pool,
            // no sid, no quota clock). always emitted so the user always has
            // a second road when every local bucket is drained.
            val fixedUrl = url.replace("/embed/", "/file/")
            val standardBase64 = Base64.encodeToString(fixedUrl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val cfWorkerLink = "https://mega.wldbs.workers.dev/download?url=$standardBase64"
            callback(newExtractorLink(name, "Mega (CF Bypass)", cfWorkerLink, ExtractorLinkType.VIDEO) {
                quality = labelQuality(linkLabel)
            })
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega CF worker link error: ${e.message}")
        }
    }
}
