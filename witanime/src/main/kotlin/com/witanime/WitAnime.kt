package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import com.lagradost.cloudstream3.mvvm.logError
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.charset.Charset

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.you"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultHeaders = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = defaultHeaders).document
        val homePageList = ArrayList<HomePageList>()
        document.select("div.main-widget").forEach { widget ->
            val title = widget.selectFirst("div.main-didget-head h3")?.text()?.trim() ?: return@forEach
            val isEpisodeList = title.contains("حلقات")
            val items = widget.select(if (isEpisodeList) "div.episodes-card-container" else "div.anime-card-container").mapNotNull {
                val a = if (isEpisodeList) it.selectFirst(".ep-card-anime-title a") else it.selectFirst("a.overlay")
                val itemUrl = a?.attr("href") ?: return@mapNotNull null
                val itemName = (if (isEpisodeList) a?.text() else it.selectFirst(".anime-card-title a")?.text()) ?: ""
                val itemPoster = it.selectFirst("img")?.attr("src")
                val finalTitle = if (isEpisodeList) "$itemName - ${it.selectFirst(".episodes-card-title a")?.text() ?: ""}" else itemName
                newAnimeSearchResponse(finalTitle, itemUrl, TvType.Anime) { posterUrl = itemPoster }
            }
            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
        }
        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?search_param=animes&s=$query", headers = defaultHeaders).document
        return document.select("div.anime-list-content div.anime-card-container").mapNotNull {
            val href = it.selectFirst("div.anime-card-poster a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("div.anime-card-title h3 a")?.text() ?: return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = it.selectFirst("img.img-responsive")?.attr("src") }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document
        val title = document.selectFirst("h1.anime-details-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.anime-thumbnail img")?.attr("src")
        val description = document.selectFirst("div.anime-details-plot")?.text()?.trim()
        val episodes = document.select("div.episodes-list a").mapNotNull { ep ->
            val epUrl = ep.attr("href")
            val epNum = ep.text().toIntOrNull()
            if (epUrl.isNotEmpty() && epNum != null) {
                newEpisode(epUrl) {
                    episode = epNum
                }
            } else null
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            this.episodes[DubStatus.Subbed] = episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = defaultHeaders).document
        document.select("div.video-player-container iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotEmpty()) {
                loadExtractor(iframeSrc, null, subtitleCallback, callback)
            }
        }
        return true
    }
}
