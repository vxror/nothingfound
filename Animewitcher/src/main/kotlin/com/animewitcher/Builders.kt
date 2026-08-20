package com.animewitcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * [!] XMAX R8/D8 METADATA CRASH PREVENTION
 * R8 crashes when inline lambdas are captured inside suspend coroutine state machines. 
 * These non-suspend wrappers isolate the inline bytecode from the coroutines.
 */
object Builders {
    fun searchResponse(title: String, url: String, poster: String?): SearchResponse {
        return newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
    }

    fun episode(data: String, name: String, number: Int): Episode {
        return newEpisode(data) { this.name = name; this.episode = number }
    }

    fun animeLoadResponse(
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

    fun subtitle(label: String, file: String, headers: Map<String, String>): SubtitleFile {
        return newSubtitleFile(label, file) { this.headers = headers }
    }
}
