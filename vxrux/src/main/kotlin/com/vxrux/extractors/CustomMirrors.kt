package com.vxrux.extractors

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.VidHidePro

// ============================================
// DOOD VARIANTS — extend built-in DoodLaExtractor
// ============================================
class Doodspro : DoodLaExtractor() { override var mainUrl = "https://doods.pro" }
class Dsvplay : DoodLaExtractor() { override var mainUrl = "https://dsvplay.com" }
class D0000d : DoodLaExtractor() { override var mainUrl = "https://d0000d.com" }
class D000dCom : DoodLaExtractor() { override var mainUrl = "https://d000d.com" }
class DoodstreamCom : DoodLaExtractor() { override var mainUrl = "https://doodstream.com" }
class Dooood : DoodLaExtractor() { override var mainUrl = "https://dooood.com" }
class DoodWf : DoodLaExtractor() { override var mainUrl = "https://dood.wf" }
class DoodCx : DoodLaExtractor() { override var mainUrl = "https://dood.cx" }
class DoodSh : DoodLaExtractor() { override var mainUrl = "https://dood.sh" }
class DoodPm : DoodLaExtractor() { override var mainUrl = "https://dood.pm" }
class DoodSo : DoodLaExtractor() { override var mainUrl = "https://dood.so" }
class DoodLi : DoodLaExtractor() { override var mainUrl = "https://dood.li" }
class Ds2video : DoodLaExtractor() { override var mainUrl = "https://ds2video.com" }
class Vide0Net : DoodLaExtractor() { override var mainUrl = "https://vide0.net" }
class MyVidPlay : DoodLaExtractor() { override var mainUrl = "https://myvidplay.com" }
class Playmogo : DoodLaExtractor() { override var mainUrl = "https://playmogo.com" }
class DoodWatch : DoodLaExtractor() { override var mainUrl = "https://dood.watch" }
class DoodWs : DoodLaExtractor() { override var mainUrl = "https://dood.ws" }
class DoodYt : DoodLaExtractor() { override var mainUrl = "https://dood.yt" }
class DoodTo : DoodLaExtractor() { override var mainUrl = "https://dood.to" }

// ============================================
// STREAMWISH VARIANTS — extend built-in StreamWishExtractor
// ============================================
class Mwish : StreamWishExtractor() { override val name = "Mwish"; override val mainUrl = "https://mwish.pro" }
class Dwish : StreamWishExtractor() { override val name = "Dwish"; override val mainUrl = "https://dwish.pro" }
class Ewish : StreamWishExtractor() { override val name = "Embedwish"; override val mainUrl = "https://embedwish.com" }
class WishembedPro : StreamWishExtractor() { override val name = "Wishembed"; override val mainUrl = "https://wishembed.pro" }
class Kswplayer : StreamWishExtractor() { override val name = "Kswplayer"; override val mainUrl = "https://kswplayer.info" }
class Wishfast : StreamWishExtractor() { override val name = "Wishfast"; override val mainUrl = "https://wishfast.top" }
class Streamwish2 : StreamWishExtractor() { override val mainUrl = "https://streamwish.site" }
class Sfastwish : StreamWishExtractor() { override val name = "Sfastwish"; override val mainUrl = "https://sfastwish.com" }
class StrwishXyz : StreamWishExtractor() { override val name = "Strwish"; override val mainUrl = "https://strwish.xyz" }
class StrwishCom : StreamWishExtractor() { override val name = "Strwish"; override val mainUrl = "https://strwish.com" }
class Flaswish : StreamWishExtractor() { override val name = "Flaswish"; override val mainUrl = "https://flaswish.com" }
class Awish : StreamWishExtractor() { override val name = "Awish"; override val mainUrl = "https://awish.pro" }
class Obeywish : StreamWishExtractor() { override val name = "Obeywish"; override val mainUrl = "https://obeywish.com" }
class Jodwish : StreamWishExtractor() { override val name = "Jodwish"; override val mainUrl = "https://jodwish.com" }
class Swhoi : StreamWishExtractor() { override val name = "Swhoi"; override val mainUrl = "https://swhoi.com" }
class UqloadsXyz : StreamWishExtractor() { override val name = "Uqloads"; override val mainUrl = "https://uqloads.xyz" }
class Cdnwish : StreamWishExtractor() { override val name = "Cdnwish"; override val mainUrl = "https://cdnwish.com" }
class Asnwish : StreamWishExtractor() { override val name = "Asnwish"; override val mainUrl = "https://asnwish.com" }
class Nekowish : StreamWishExtractor() { override val name = "Nekowish"; override val mainUrl = "https://nekowish.my.id" }
class Nekostream : StreamWishExtractor() { override val name = "Nekostream"; override val mainUrl = "https://neko-stream.click" }
class Swdyu : StreamWishExtractor() { override val name = "Swdyu"; override val mainUrl = "https://swdyu.com" }
class Wishonly : StreamWishExtractor() { override val name = "Wishonly"; override val mainUrl = "https://wishonly.site" }
class Playerwish : StreamWishExtractor() { override val name = "Playerwish"; override val mainUrl = "https://playerwish.com" }
class StreamHLS : StreamWishExtractor() { override val name = "StreamHLS"; override val mainUrl = "https://streamhls.to" }
class HlsWish : StreamWishExtractor() { override val name = "HlsWish"; override val mainUrl = "https://hlswish.com" }

// ============================================
// FILEMOON VARIANTS — extend built-in FilemoonV2
// ============================================
class FileMoonIn : FilemoonV2() { override var mainUrl = "https://filemoon.in"; override var name = "FileMoon" }
class FileMoonSx : FilemoonV2() { override var mainUrl = "https://filemoon.sx"; override var name = "FileMoonSx" }
class FileMoonTo : FilemoonV2() { override var mainUrl = "https://filemoon.to"; override var name = "FileMoon" }

// ============================================
// CINEMM / VIDHIDE PRO MIRRORS — extend built-in VidHidePro
// ============================================
class HgplayCDN : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://hgplaycdn.com" }
class Habetar : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://habetar.com" }
class Yuguaab : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://yuguaab.com" }
class Guxhag : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://guxhag.com" }
class Auvexiug : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://auvexiug.com" }
class Xenolyzb : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://xenolyzb.com" }
class Haxloppd : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://haxloppd.com" }
class Cavanhabg : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://cavanhabg.com" }
class Dumbalag : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://dumbalag.com" }
class Uasopt : VidHidePro() { override val name = "CineMM"; override val mainUrl = "https://uasopt.com" }

// ============================================
// CLOUDMAILRU — custom extractor (not built into CloudStream)
// ============================================
class CloudMailRu : ExtractorApi() {
    override val name = "CloudMailRu"
    override val mainUrl = "https://cloud.mail.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
            )
            val vidId = url.substringAfter("public/").encodeToByteArray()
            val vidIdEnc = Base64.encodeToString(vidId, Base64.NO_WRAP)
            val videoReq = app.get(url, headers = headers).text
            val videoMatch = Regex("videowl_view\":\\{\"count\":\"1\",\"url\":\"([^\"]*)\"\\}")
                .find(videoReq)?.groupValues?.get(1) ?: return
            val videoUrl = "$videoMatch/0p/$vidIdEnc.m3u8?double_encode=1"

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        } catch (_: Exception) {}
    }
}
