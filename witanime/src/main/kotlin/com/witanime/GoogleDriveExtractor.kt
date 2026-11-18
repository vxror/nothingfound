package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class GoogleDriveExtractor : ExtractorApi() {
    override val name = "GoogleDrive"
    override val mainUrl = "https://drive.google.com"
    override val requiresReferer = false
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""(?:/file/d/|[?&]id=)([0-9A-Za-z_-]{15,})""").find(url)?.groupValues?.get(1) ?: return
        val qName = linkLabel?.trim()?.ifBlank { null }
        callback(newExtractorLink(name, name + (qName?.let { " $it" } ?: ""),
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t", ExtractorLinkType.VIDEO) {
            this.referer = "https://drive.google.com/"
            quality = labelQuality(linkLabel)
        })
    }
}
