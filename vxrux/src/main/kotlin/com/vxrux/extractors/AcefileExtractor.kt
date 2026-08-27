package com.vxrux.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

open class AcefileBase : ExtractorApi() {
    override val name = "Acefile"
    override val mainUrl = "https://acefile.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val id = Regex("/(?:d|download|player|f|file)/(\\w+)").find(url)?.groupValues?.get(1) ?: return
            val script = getAndUnpack(app.get("$mainUrl/player/$id").text) ?: return
            val service = Regex("""service\s*=\s*['"]([^'"]+)""").find(script)?.groupValues?.get(1) ?: return
            val serverUrl = Regex("""['"](\S+check&id\S+?)['"]""").find(script)?.groupValues?.get(1)
                ?.replace("\"+service+\"", service) ?: return

            val video = tryParseJson<Source>(app.get(serverUrl, referer = "$mainUrl/").text)?.data ?: return

            callback.invoke(
                newExtractorLink(this.name, this.name, video, ExtractorLinkType.VIDEO)
            )
        } catch (_: Exception) {}
    }

    data class Source(
        @JsonProperty("data") val data: String? = null,
    )
}

class AcefileExtractor : AcefileBase()
