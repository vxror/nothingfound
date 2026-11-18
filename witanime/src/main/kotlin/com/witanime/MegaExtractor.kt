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
        if (!WitaState.isCasting) {
            try {
                val local = MegaProxy.resolve(url)
                if (local != null) {
                    callback(newExtractorLink(name, "Mega", local, ExtractorLinkType.VIDEO) { quality = labelQuality(linkLabel) })
                }
            } catch (e: Exception) { WitaLog.e("Mega local proxy error: ${e.message}") }
        }
        try {
            val fixedUrl = url.replace("/embed/", "/file/")
            val b64 = Base64.encodeToString(fixedUrl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            callback(newExtractorLink(name, "Mega (CF Bypass)",
                "https://mega.wldbs.workers.dev/download?url=$b64", ExtractorLinkType.VIDEO) { quality = labelQuality(linkLabel) })
        } catch (e: Exception) { WitaLog.e("Mega CF worker link error: ${e.message}") }
    }
}
