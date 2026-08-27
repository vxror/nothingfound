package com.vxrux

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.vxrux.extractors.*

@CloudstreamPlugin
class VxruxPlugin : Plugin() {
    override fun load(context: Context) {
        // Dood variants
        registerExtractorAPI(Doodspro())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(D0000d())
        registerExtractorAPI(D000dCom())
        registerExtractorAPI(DoodstreamCom())
        registerExtractorAPI(Dooood())
        registerExtractorAPI(DoodWf())
        registerExtractorAPI(DoodCx())
        registerExtractorAPI(DoodSh())
        registerExtractorAPI(DoodPm())
        registerExtractorAPI(DoodSo())
        registerExtractorAPI(DoodLi())
        registerExtractorAPI(Ds2video())
        registerExtractorAPI(Vide0Net())
        registerExtractorAPI(MyVidPlay())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(DoodWatch())
        registerExtractorAPI(DoodWs())
        registerExtractorAPI(DoodYt())
        registerExtractorAPI(DoodTo())

        // StreamWish variants
        registerExtractorAPI(Mwish())
        registerExtractorAPI(Dwish())
        registerExtractorAPI(Ewish())
        registerExtractorAPI(WishembedPro())
        registerExtractorAPI(Kswplayer())
        registerExtractorAPI(Wishfast())
        registerExtractorAPI(Streamwish2())
        registerExtractorAPI(Sfastwish())
        registerExtractorAPI(StrwishXyz())
        registerExtractorAPI(StrwishCom())
        registerExtractorAPI(Flaswish())
        registerExtractorAPI(Awish())
        registerExtractorAPI(Obeywish())
        registerExtractorAPI(Jodwish())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(UqloadsXyz())
        registerExtractorAPI(Cdnwish())
        registerExtractorAPI(Asnwish())
        registerExtractorAPI(Nekowish())
        registerExtractorAPI(Nekostream())
        registerExtractorAPI(Swdyu())
        registerExtractorAPI(Wishonly())
        registerExtractorAPI(Playerwish())
        registerExtractorAPI(StreamHLS())
        registerExtractorAPI(HlsWish())

        // FileMoon variants
        registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(FileMoonTo())

        // CineMM / VidHide mirrors
        registerExtractorAPI(HgplayCDN())
        registerExtractorAPI(Habetar())
        registerExtractorAPI(Yuguaab())
        registerExtractorAPI(Guxhag())
        registerExtractorAPI(Auvexiug())
        registerExtractorAPI(Xenolyzb())
        registerExtractorAPI(Haxloppd())
        registerExtractorAPI(Cavanhabg())
        registerExtractorAPI(Dumbalag())
        registerExtractorAPI(Uasopt())

        // CloudMailRu (custom — not built-in)
        registerExtractorAPI(CloudMailRu())

        println("VxruxDebug: All extractors registered!")
    }
}
