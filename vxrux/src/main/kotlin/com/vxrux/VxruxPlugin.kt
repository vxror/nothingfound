package com.vxrux

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class VxruxPlugin : Plugin() {
    override fun load(context: Context) {
        val extractors = listOf<ExtractorApi>(
            Acefile(), Blogger(), Bysezejataos(), ByseBuho(), ByseVepoin(), ByseQekaho(), ByseSX(),
            Cda(), HgplayCDN(), Habetar(), Yuguaab(), Guxhag(), Auvexiug(), Xenolyzb(), Haxloppd(),
            Cavanhabg(), Dumbalag(), Uasopt(), Dhcplay(), HglinkTo(), CloudMailRu(), ContentX(),
            Dailymotion(), Geodailymotion(), Doodspro(), Dsvplay(), D0000d(), D000dCom(),
            DoodstreamCom(), Dooood(), DoodWfExtractor(), DoodCxExtractor(), DoodShExtractor(),
            DoodWatchExtractor(), DoodPmExtractor(), DoodToExtractor(), DoodSoExtractor(),
            DoodWsExtractor(), DoodYtExtractor(), DoodLiExtractor(), Ds2play(), Ds2video(),
            Vide0Net(), MyVidPlay(), Playmogo(), DoodLaExtractor(), Embedgram(), EmturbovidExtractor(),
            Evoload1(), Evoload(), Fastream(), Filegram(), FileMoon(), FileMoonIn(), FileMoonSx(),
            FilemoonV2(), Multimoviesshg(), Guccihide(), Ahvsh(), Moviesm4u(), StreamhideTo(),
            StreamhideCom(), Movhide(), Ztreamhub(), Filesim(), Firestream(), Flyfile(),
            GDMirrorbot(), Techinmind(), GUpload(), GamoVideo(), DatabaseGdrive2(), DatabaseGdrive(),
            Gdriveplayerapi(), Gdriveplayerapp(), Gdriveplayerfun(), Gdriveplayerio(),
            Gdriveplayerme(), Gdriveplayerbiz(), Gdriveplayerorg(), Gdriveplayerus(),
            Gdriveplayerco(), Gdriveplayer(), Gofile(), GoodstreamExtractor(),
            HDMomPlayer(), HDPlayerSystem(), HDStreamAble(), Hotlinger(), FourCX(), PlayRu(),
            FourPlayRu(), Pichive(), FourPichive(), HubCloud(), HubuCloud(), Hxfile(), Neonime7n(),
            Neonime8n(), KotakAnimeid(), Yufiles(), Aico(), InternetArchive(),
            JWPlayer(), Meownime(), DesuOdchan(), DesuArcg(), DesuDrive(), DesuOdvip(), VidNest(),
            BigwarpIO(), BgwpCC(), BigwarpArt(), Jeniusplay(), Krakenfiles(), Linkbox(),
            Luluvdoo(), Lulustream1(), Lulustream2(), LuluStream(), MailRu(), Maxstream(),
            Mediafire(), MixDropPs(), Mdy(), MxDropTo(), MixDropSi(), MixDropBz(), MixDropAg(),
            MixDropCh(), MixDropTo(), MixDrop(), MoviehabNet(), Moviehab(), Mp4Upload(), Mvidoo(),
            Odnoklassniki(), OkRuSSL(), OkRuHTTP(), OkRuSSLMobile(), OkRuHTTPMobile(),
            PeaceMakerst(), PixelDrainDev(), PixelDrain(), PlayLtXyz(), PlayerVoxzer(), Playmate(),
            Megacloud(), Dokicloud(), Rabbitstream(), RapidVid(), SBPlay(), SecvideoOnline(),
            FsstOnline(), CsstOnline(), DsstOnline(), Sendvid(), SibNet(), Sobreatsesuyp(),
            StreamEmbed(), Sblona(), Lvturbo(), Sbrapid(), Sbface(), Sbsonic(), Vidgomunimesb(),
            Sbasian(), Sbnet(), Keephealth(), Sbspeed(), Streamsss(), Sbflix(), Vidgomunime(),
            Sbthe(), Ssbstream(), SBfull(), StreamSB1(), StreamSB2(), StreamSB3(), StreamSB4(),
            StreamSB5(), StreamSB6(), StreamSB7(), StreamSB8(), StreamSB9(), StreamSB10(),
            StreamSB11(), Sblongvu(), StreamSB(), StreamSilk(), Watchadsontape(), StreamTapeNet(),
            StreamTapeXyz(), ShaveTape(), StreamTape(), Mwish(), Dwish(), Ewish(), Hgcloudto(),
            WishembedPro(), Kswplayer(), Wishfast(), Streamwish2(), SfastwishCom(), Strwish(),
            Strwish2(), FlaswishCom(), Awish(), Obeywish(), Jodwish(), Swhoi(), Multimovies(),
            UqloadsXyz(), Doodporn(), CdnwishCom(), Asnwish(), Nekowish(), Nekostream(), Swdyu(),
            Wishonly(), Playerwish(), StreamHLS(), HlsWish(), StreamWishExtractor(), Streamcash(),
            Streamhub(), Streamlare(), Slmaxed(), StreamoUpload(), Streamplay(), Supervideo(),
            TRsTX(), Tantifilm(), TauVideo(), Up4FunTop(), Up4Stream(), UpstreamExtractor(),
            Uqload1(), Uqload2(), Uqloadcx(), Uqloadbz(), Uqload(), Userload(), Userscloud(),
            Uservideo(), Vicloud(), Ryderjet(), VidHideHub(), VidHidePro1(), VidHidePro2(),
            VidHidePro3(), VidHidePro4(), VidHidePro5(), VidHidePro6(), Smoothpre(), Dhtpre(),
            Peytonepre(), VidHidePro(), VidMoxy(), Server1uns(), VidStack(), Vidavaca(),
            VidaaraxNet(), VidaaraxCom(), Vidaratem(), Vidaraw(), Vidarax(), Vidaraa(), VidaraSo(),
            Odysseusa(), Handfacesnap(), Namefacesnap(), Thebesthostertv(), Vidmatrixa(),
            VidChampions(), Antarcticadocs(), Nameitweb(), Vidara(), Videa(), VideoSeyred(),
            VidhideExtractor(), Vidmolyme(), Vidmolyto(), Vidmolybiz(), Vidmoly(), Vido(), Videzz(),
            Vidoza(), Vids(), Vidsonic(), VinovoSi(), VinovoTo(), VkExtractor(), Tubeless(),
            Simpulumlamerop(), Urochsunloath(), NathanFromSubject(), Yipsu(), MetaGnathTuggers(),
            Voe1(), Voe2(), Voe(), Vtbe(), WatchSB(), Wibufile(), StreamM4u(), Fembed9hd(),
            Cdnplayer(), Kotakajair(), FEnet(), Rasacintaku(), LayarKaca(), DBfilm(), Luxubu(),
            FEmbed(), Fplayer(), FeHD(), XStreamCdn(), YourUpload(), YoutubeShortLinkExtractor(),
            YoutubeMobileExtractor(), YoutubeNoCookieExtractor(), YoutubeExtractor(), Zplayer(),
            Upstream(), Streamhub2(), ZplayerV2()
        )
        
        extractors.forEach { registerExtractorApi(it) }
    }
}
