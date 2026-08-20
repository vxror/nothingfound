package com.animewitcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// TOP-LEVEL FUNCTIONS. 
// These isolate the inline builder lambdas from the coroutine state machines,
// preventing the D8/R8 metadata crash while satisfying Kotlin's `val` rules.

fun buildSearchResponse(title: String, url: String, poster: String?): SearchResponse {
    return newAnimeSearchResponse(title, url, TvType.Anime) {
        this.posterUrl = poster
    }
}

fun buildEpisode(data: String, name: String, number: Int): Episode {
    return newEpisode(data) {
        this.name = name
        this.episode = number
    }
}

fun buildAnimeLoadResponse(
    name: String, url: String, poster: String?, year: Int?, plot: String, 
    status: ShowStatus, tags: List<String>, episodes: List<Episode>
): LoadResponse {
    return newAnimeLoadResponse(name, url, TvType.Anime) {
        this.posterUrl = poster
        this.year = year
        this.plot = plot
        this.showStatus = status
        this.tags = tags
        addEpisodes(DubStatus.Subbed, episodes)
    }
}

fun buildSubtitle(label: String, file: String, headers: Map<String, String>): SubtitleFile {
    return newSubtitleFile(label, file) {
        this.headers = headers
    }
}

fun buildLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    quality: Int = Qualities.Unknown.value,
    referer: String? = null,
    headers: Map<String, String> = emptyMap()
): ExtractorLink {
    return newExtractorLink(source, name, url, type) {
        this.quality = quality
        this.referer = referer ?: ""
        this.headers = headers
    }
}
