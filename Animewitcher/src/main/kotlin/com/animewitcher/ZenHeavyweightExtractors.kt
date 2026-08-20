package com.animewitcher

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object ZenHeavyweightExtractors {
    
    // ==================== UNIVERSAL DIRECT LINK DETECTOR ====================
    
    /**
     * 🆕 UNIVERSAL: Extract direct video links from ANY page.
     * Works like copying from browser DevTools.
     */
    private suspend fun extractDirectLink(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8"
            )
            
            val response = app.get(url, headers = headers, referer = referer, allowRedirects = true)
            val html = response.text
            
            // Multiple regex patterns for different video formats
            val patterns = listOf(
                // Direct MP4/MKV/AVI/WebM links
                Regex("""(https?://[^\s"'<>]+\.(?:mp4|mkv|avi|webm)[^\s"'<>]*)""", RegexOption.IGNORE_CASE),
                // M3U8 streams
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE),
                // Video CDN patterns
                Regex("""(https?://[^\s"'<>]+/(?:video|stream|media|play)/[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""", RegexOption.IGNORE_CASE),
                // JW Player sources
                Regex("""(?:file|source|src)\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
                // Video.js sources
                Regex("""<source\s+src=["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
                // HTML5 video tags
                Regex("""<video[^>]+src=["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            )
            
            val foundLinks = mutableSetOf<String>()
            
            for (pattern in patterns) {
                pattern.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    if (ZenCore.isValidVideoUrl(videoUrl)) {
                        foundLinks.add(videoUrl)
                    }
                }
            }
            
            // Handle P.A.C.K.E.R. obfuscation
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html)
            if (packed != null) {
                val decoded = ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
                patterns.forEach { pattern ->
                    pattern.findAll(decoded).forEach { match ->
                        val videoUrl = match.groupValues[1].replace("\\/", "/")
                        if (ZenCore.isValidVideoUrl(videoUrl)) {
                            foundLinks.add(videoUrl)
                        }
                    }
                }
            }
            
            // Handle base64 encoded URLs
            Regex("""(?:atob|base64decode|decodeURIComponent)\s*\(\s*["']([A-Za-z0-9+/=]{20,})["']\s*\)""")
                .findAll(html).forEach { match ->
                    try {
                        val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                        if (ZenCore.isValidVideoUrl(decoded)) {
                            foundLinks.add(decoded)
                        }
                    } catch (_: Exception) {}
                }
            
            if (foundLinks.isNotEmpty()) {
                for (link in foundLinks) {
                    val type = when {
                        link.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }
                    
                    val detectedQuality = ZenCore.detectQualityFromUrl(link) ?: quality
                    
                    callback.invoke(newExtractorLink(
                        source = sourceName,
                        name = "$sourceName Direct",
                        url = link,
                        type = type
                    ) {
                        this.referer = url
                        this.quality = detectedQuality
                        this.headers = headers
                    })
                }
                return true
            }
            false
        } catch (e: Exception) {
            ZenCore.logError("extractDirectLink", e)
            false
        }
    }
    
    // ==================== PIXELDRAIN (PD Fast — RESTORED) ====================
    
    /**
     * ✅ RESTORED: PixelDrain with cdn.pixeldrain.eu.cc mirror + Fast Mode
     */
    private suspend fun extractPixelDrain(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return false
            
            // 🆕 Multiple mirrors (INCLUDING ORIGINAL cdn.pixeldrain.eu.cc)
            val mirrors = listOf(
                "https://cdn.pixeldrain.eu.cc/$id", // ✅ ORIGINAL MIRROR RESTORED
                "https://pixeldrain.com/api/file/$id"
            )
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Referer" to "https://pixeldrain.com/"
            )
            
            var extracted = false
            
            // Try each mirror
            for (mirrorUrl in mirrors) {
                try {
                    val res = app.get(mirrorUrl, headers = headers, timeout = 8000)
                    if (res.code == 200) {
                        callback.invoke(newExtractorLink(
                            source = sourceName,
                            name = "$sourceName PixelDrain",
                            url = mirrorUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.referer = "https://pixeldrain.com/"
                            this.headers = headers
                        })
                        extracted = true
                        break
                    }
                } catch (_: Exception) {
                    continue
                }
            }
            
            // Fallback: DOM scraping
            if (!extracted) {
                try {
                    val html = app.get(url, referer = referer ?: "https://pixeldrain.com/").text
                    val videoUrl = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(html)?.groupValues?.get(1)
                    
                    if (videoUrl != null) {
                        callback.invoke(newExtractorLink(
                            source = sourceName,
                            name = "$sourceName PixelDrain",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.referer = "https://pixeldrain.com/"
                            this.headers = headers
                        })
                        extracted = true
                    }
                } catch (_: Exception) {}
            }
            
            extracted
        } catch (e: Exception) {
            ZenCore.logError("extractPixelDrain", e)
            false
        }
    }
    
    // ==================== STREAMTAPE (RESTORED) ====================
    
    private suspend fun extractStreamTape(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            
            // Multiple extraction patterns
            val patterns = listOf(
                // Pattern 1: robotlink method
                Regex("""document\.getElementById\('robotlink'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\.substr\(0,\s*(\d+)\)"""),
                // Pattern 2: Direct link in script
                Regex("""['"]([^'']*streamtape[^'']*\.mp4[^'']*)['"]"""),
                // Pattern 3: Alternative format
                Regex("""['"]([^'']*\.mp4[^'']*streamtape[^'']*)['"]"""),
                // Pattern 4: Normal video link
                Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val videoUrl = when {
                        match.groupValues.size >= 3 -> {
                            val part1 = match.groupValues[1]
                            val part2 = match.groupValues[2]
                            val substrLen = match.groupValues[3].toIntOrNull() ?: 0
                            "https:" + part1 + part2.substring(substrLen)
                        }
                        else -> {
                            val link = match.groupValues[1]
                            if (link.startsWith("//")) "https:$link" else link
                        }
                    }
                    
                    callback.invoke(newExtractorLink(
                        source = sourceName,
                        name = "$sourceName StreamTape",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = url
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    })
                    return true
                }
            }
            false
        } catch (e: Exception) {
            ZenCore.logError("extractStreamTape", e)
            false
        }
    }
    
    // ==================== MEDIAFIRE (RESTORED) ====================
    
    private suspend fun extractMediaFire(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            
            val patterns = listOf(
                Regex("""href\s*=\s*"(https?://download[^"]+\.mediafire[^"]*)""""),
                Regex("""href\s*=\s*"(https?://[^"]*mediafire[^"]*download[^"]*)""""),
                Regex("""['"](https?://[^"']*mediafire[^"']*\.mp4[^"']*)['"]""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val directLink = match.groupValues[1]
                    
                    // Follow redirect
                    val finalUrl = try {
                        val res = app.get(directLink, allowRedirects = true)
                        res.url
                    } catch (e: Exception) {
                        directLink
                    }
                    
                    callback.invoke(newExtractorLink(
                        source = sourceName,
                        name = "$sourceName MediaFire",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = "https://www.mediafire.com/"
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    })
                    return true
                }
            }
            
            // Fallback: Download button
            val doc = Jsoup.parse(html)
            val downloadBtn = doc.selectFirst("a.download-btn, a#download-btn, a[href*='download']")
            downloadBtn?.attr("href")?.let { href ->
                if (href.contains("mediafire") || href.contains(".mp4")) {
                    val fullUrl = if (href.startsWith("http")) href else "https://www.mediafire.com$href"
                    callback.invoke(newExtractorLink(
                        source = sourceName,
                        name = "$sourceName MediaFire",
                        url = fullUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = "https://www.mediafire.com/"
                    })
                    return true
                }
            }
            false
        } catch (e: Exception) {
            ZenCore.logError("extractMediaFire", e)
            false
        }
    }
    
    // ==================== NEW EXTRACTORS (15+) ====================
    
    /**
     * 🆕 MP4Upload
     */
    private suspend fun extractMp4Upload(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html)
            
            val text = if (packed != null) {
                ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
            } else {
                html
            }
            
            val videoUrl = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(text)?.groupValues?.get(1)
            
            if (videoUrl != null) {
                ZenDeliveryEngine.deliverSmartLink(sourceName, "MP4Upload", videoUrl, referer, quality, callback)
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🆕 StreamSB
     */
    private suspend fun extractStreamSB(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val patterns = listOf(
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
                Regex("""file\s*:\s*["']([^"']+)["']"""),
                Regex("""sources\s*:\s*\[\{[^}]*file\s*:\s*["']([^"']+)["']""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                        ZenDeliveryEngine.deliverSmartLink(sourceName, "StreamSB", videoUrl, referer, quality, callback)
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🆕 DoodStream
     */
    private suspend fun extractDoodStream(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedUrl = url.replace("/d/", "/e/").replace("/w/", "/e/")
            val html = app.get(embedUrl, referer = referer).text
            
            val md5Hash = Regex("""'/pass_md5/([^']+)'""").find(html)?.groupValues?.get(1)
            
            if (md5Hash != null) {
                val baseUrl = URI(embedUrl).let { "${it.scheme}://${it.host}" }
                val passUrl = "$baseUrl/pass_md5/$md5Hash"
                val passResponse = app.get(passUrl, referer = embedUrl).text
                
                val randomToken = (1..10).map { 
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(62)] 
                }.joinToString("")
                
                val videoUrl = "${passResponse}.$randomToken"
                
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    ZenDeliveryEngine.deliverSmartLink(sourceName, "DoodStream", videoUrl, embedUrl, quality, callback)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🆕 Ok.ru
     */
    private suspend fun extractOkRu(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val videos = Regex("""\{"name":"([^"]+)","url":"([^"]+)"\}""").findAll(html)
            
            var found = false
            videos.forEach { match ->
                val qualityName = match.groupValues[1]
                val videoUrl = match.groupValues[2].replace("\\/", "/")
                
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    ZenDeliveryEngine.deliverSmartLink(
                        sourceName, "OK.ru $qualityName", videoUrl, referer, quality, callback
                    )
                    found = true
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🆕 Uqload
     */
    private suspend fun extractUqload(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val patterns = listOf(
                Regex("""sources\s*:\s*\[\s*["']([^"']+\.mp4[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
            )
            
            for (pattern in patterns) {
                pattern.find(html)?.let { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    ZenDeliveryEngine.deliverSmartLink(sourceName, "Uqload", videoUrl, referer, quality, callback)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🆕 FileMoon
     */
    private suspend fun extractFileMoon(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html)
            
            val text = if (packed != null) {
                ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
            } else {
                html
            }
            
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(text)?.let { match ->
                ZenDeliveryEngine.deliverSmartLink(
                    sourceName, "FileMoon", match.groupValues[1], referer, quality, callback
                )
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🆕 StreamWish
     */
    private suspend fun extractStreamWish(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val patterns = listOf(
                Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
            )
            
            for (pattern in patterns) {
                pattern.find(html)?.let { match ->
                    ZenDeliveryEngine.deliverSmartLink(
                        sourceName, "StreamWish", match.groupValues[1], referer, quality, callback
                    )
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🆕 Vidhide
     */
    private suspend fun extractVidhide(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html)
            
            val text = if (packed != null) {
                ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
            } else {
                html
            }
            
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(text)?.let { match ->
                ZenDeliveryEngine.deliverSmartLink(
                    sourceName, "Vidhide", match.groupValues[1], referer, quality, callback
                )
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    // ==================== MAIN DISPATCHER ====================
    
    suspend fun tryExtract(
        host: String, url: String, referer: String?,
        sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                // ✅ RESTORED EXTRACTORS
                host.contains("pixeldrain") || host.contains("pd.cyberdrop") || 
                host.contains("cdn.pixeldrain") -> 
                    extractPixelDrain(url, referer, sourceName, quality, callback)
                
                host.contains("streamtape") || host.contains("stp") || 
                host.contains("streamta") || host.contains("tape") -> 
                    extractStreamTape(url, referer, sourceName, quality, callback)
                
                host.contains("mediafire") || host.contains("mfcdn") -> 
                    extractMediaFire(url, referer, sourceName, quality, callback)
                
                // ✅ ORIGINAL EXTRACTORS (PRESERVED)
                host.contains("dailymotion") || host.contains("dai.ly") -> 
                    extractDailymotion(url, sourceName, quality, callback)
                
                host.contains("voe.sx") || host.contains("voe.") || 
                host.contains("voe-unblock") -> 
                    extractVoe(url, sourceName, quality, callback)
                
                host.contains("videa.hu") -> 
                    extractVidea(url, referer, sourceName, quality, callback)
                
                host.contains("vk.com") || host.contains("vkvideo.ru") || 
                host.contains("my.mail.ru") -> 
                    extractVKAndMailRu(url, referer, sourceName, quality, callback)
                
                host.contains("vidmoly") -> 
                    extractVidmoly(url, referer, sourceName, quality, callback)
                
                host.contains("luluvid") || host.contains("luluvdo") -> 
                    extractLuluvid(url, referer, sourceName, quality, callback)
                
                host.contains("hgcloud") || host.contains("streamhg") -> 
                    extractStreamHG(url, referer, sourceName, quality, callback)
                
                host.contains("vidguard") || host.contains("listeamed") -> 
                    extractVidguard(url, referer, sourceName, quality, callback)
                
                // 🆕 NEW EXTRACTORS
                host.contains("mp4upload") -> 
                    extractMp4Upload(url, referer, sourceName, quality, callback)
                
                host.contains("streamsb") || host.contains("sbplay") || 
                host.contains("sbanh") -> 
                    extractStreamSB(url, referer, sourceName, quality, callback)
                
                host.contains("dood") || host.contains("dstream") || 
                host.contains("dooood") -> 
                    extractDoodStream(url, referer, sourceName, quality, callback)
                
                host.contains("ok.ru") || host.contains("odnoklassniki") -> 
                    extractOkRu(url, referer, sourceName, quality, callback)
                
                host.contains("uqload") || host.contains("uqload.io") -> 
                    extractUqload(url, referer, sourceName, quality, callback)
                
                host.contains("filemoon") || host.contains("moonplayer") -> 
                    extractFileMoon(url, referer, sourceName, quality, callback)
                
                host.contains("streamwish") || host.contains("wishfast") -> 
                    extractStreamWish(url, referer, sourceName, quality, callback)
                
                host.contains("vidhide") || host.contains("vidhidepro") -> 
                    extractVidhide(url, referer, sourceName, quality, callback)
                
                // 🆕 UNIVERSAL FALLBACK
                else -> extractDirectLink(url, referer, sourceName, quality, callback)
            }
        } catch (e: Exception) {
            ZenCore.logError("tryExtract:$host", e)
            // Last resort: Universal direct link extraction
            try {
                extractDirectLink(url, referer, sourceName, quality, callback)
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    // ==================== ORIGINAL EXTRACTORS (PRESERVED) ====================
    
    private suspend fun extractDailymotion(
        url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val idRegex = Regex("""[?&]video=([^&]+)|/video/([a-zA-Z0-9]+)|dai\.ly/([a-zA-Z0-9]+)""")
        val match = idRegex.find(url) ?: return false
        val id = match.groupValues[1].ifEmpty {
            match.groupValues[2].ifEmpty { match.groupValues[3] }
        }
        if (id.isBlank()) return false
        
        val meta = JSONObject(
            app.get(
                "https://www.dailymotion.com/player/metadata/video/$id",
                referer = "https://www.dailymotion.com/embed/video/$id"
            ).text
        )
        
        if (meta.has("error")) return false
        
        // Enhanced: Check all quality levels
        val qualities = meta.optJSONObject("qualities") ?: return false
        val keys = qualities.keys()
        
        while (keys.hasNext()) {
            val qualityKey = keys.next()
            val qualityArray = qualities.optJSONArray(qualityKey)
            
            for (i in 0 until (qualityArray?.length() ?: 0)) {
                val videoUrl = qualityArray?.optJSONObject(i)?.optString("url")
                if (!videoUrl.isNullOrBlank() && videoUrl.contains(".m3u8")) {
                    val qualityInt = qualityKey.toIntOrNull() ?: quality
                    ZenDeliveryEngine.deliverSmartLink(
                        sourceName, "Dailymotion ${qualityKey}p",
                        videoUrl, null, qualityInt, callback
                    )
                }
            }
        }
        return true
    }
    
    private suspend fun extractVoe(
        url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = app.get(url, referer = url).text
        val link = Regex("""https?://[^"\' ]+\.m3u8[^"\' ]*""").find(text)?.value
            ?: Regex("""file:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
        
        if (link != null) {
            ZenDeliveryEngine.deliverSmartLink(sourceName, "Voe", link, null, quality, callback)
            return true
        }
        return false
    }
    
    private suspend fun extractVidea(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val STUPID_KEY = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"
        val iframeSrc = url.trim()
        val pageResp = app.get(iframeSrc).text
        val nonce = Regex("""_xt\s*=\s*"([^"]+)"""").find(pageResp)?.groupValues?.get(1) ?: return false
        val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
        val paramSPart = if (nonce.length > 32) nonce.substring(32) else ""
        val resultBuilder = StringBuilder()
        
        for (i in 0 until 32) {
            val ch = paramL.getOrNull(i) ?: 'a'
            val idxInStupid = STUPID_KEY.indexOf(ch).takeIf { it >= 0 } ?: 0
            val index = i - (idxInStupid - 31)
            val safeIndex = when {
                paramSPart.isEmpty() -> 0
                index < 0 -> 0
                index >= paramSPart.length -> paramSPart.length - 1
                else -> index
            }
            resultBuilder.append(paramSPart.getOrNull(safeIndex) ?: 'a')
        }
        
        val result = resultBuilder.toString()
        val seed = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        val paramT = if (result.length >= 16) result.substring(0, 16) else result.padEnd(16, '0')
        val rc4KeyPart = if (result.length > 16) result.substring(16) else ""
        val videoId = try {
            URI(iframeSrc).query?.split("&")?.find { it.startsWith("v=") }?.substringAfter("=")
        } catch (e: Exception) { null } ?: return false
        
        val xmlResponse = app.get(
            "https://videa.hu/player/xml?platform=desktop&_s=$seed&_t=$paramT&v=$videoId",
            headers = mapOf("Referer" to iframeSrc, "Origin" to "https://videa.hu")
        )
        val xVideaXsHeader = xmlResponse.headers["x-videa-xs"] ?: ""
        
        val finalDoc = if (xmlResponse.text.trimStart().startsWith("<?xml")) {
            Jsoup.parse(xmlResponse.text, "", Parser.xmlParser())
        } else {
            val decoded = Base64.decode(xmlResponse.text.trim(), Base64.DEFAULT)
            val spec = SecretKeySpec((rc4KeyPart + seed + xVideaXsHeader).toByteArray(StandardCharsets.UTF_8), "RC4")
            val cipher = Cipher.getInstance("RC4")
            cipher.init(Cipher.DECRYPT_MODE, spec)
            Jsoup.parse(String(cipher.update(decoded) ?: cipher.doFinal(decoded), StandardCharsets.UTF_8), "", Parser.xmlParser())
        }
        
        val videoSources = finalDoc.select("video_source")
        if (videoSources.isEmpty()) return false
        
        for (src in videoSources) {
            val name = src.attr("name")
            val videoUrlPart = src.text().trim()
            val exp = src.attr("exp")
            val md5 = finalDoc.getElementsByTag("hash_value_$name").first()?.text()?.trim() ?: continue
            val finalUrl = if (videoUrlPart.startsWith("http")) {
                "$videoUrlPart?md5=$md5&expires=$exp"
            } else {
                "https:$videoUrlPart?md5=$md5&expires=$exp"
            }
            ZenDeliveryEngine.deliverSmartLink(sourceName, "Videa $name", finalUrl, iframeSrc, quality, callback)
        }
        return true
    }
    
    private suspend fun extractVKAndMailRu(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (url.contains("my.mail.ru")) {
            val vidId = url.substringAfter("video/embed/").trim()
            if (vidId.isBlank()) return false
            
            val videoReq = app.get("https://my.mail.ru/+/video/meta/$vidId", referer = url)
            val videoKey = videoReq.cookies["video_key"]?.toString().orEmpty()
            val videos = JSONObject(videoReq.text).optJSONArray("videos") ?: return false
            
            for (i in 0 until videos.length()) {
                val video = videos.getJSONObject(i)
                var videoUrl = video.optString("url")
                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
                if (videoKey.isNotBlank()) {
                    videoUrl += (if (videoUrl.contains("?")) "&" else "?") + "video_key=$videoKey"
                }
                ZenDeliveryEngine.deliverSmartLink(sourceName, "MailRu", videoUrl, url, quality, callback)
            }
            return true
        }
        
        val headers = mapOf(
            "Referer" to (referer ?: "https://vk.com/"),
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        
        val html = app.get(url, headers = headers).text
        if (html.length < 500) return false
        
        val unescape = { s: String ->
            s.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        }
        
        Regex("""\\?"hls\\?":\\?"(https?:(?:\\/|[^"\\])+)""").find(html)?.groupValues?.get(1)?.let { raw ->
            val adaptiveUrl = ZenAdaptivePicker.selectBestVariant(unescape(raw), headers) ?: unescape(raw)
            ZenDeliveryEngine.deliverSmartLink(sourceName, "VK HLS", adaptiveUrl, url, quality, callback)
            return true
        }
        
        Regex("""\\?"url(\d{3,4})\\?":\\?"(https?:(?:\\/|[^"\\])+)""").findAll(html).forEach { m ->
            val q = m.groupValues[1].toIntOrNull() ?: 0
            ZenDeliveryEngine.deliverSmartLink(sourceName, "VK ${q}p", unescape(m.groupValues[2]), url, q, callback)
        }
        return true
    }
    
    private suspend fun extractVidmoly(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val script = app.get(url, referer = referer).document.select("script")
            .find { it.data().contains("sources:") }?.data() ?: return false
        val match = Regex("""file\s*:\s*["'](http[^"']+\.m3u8[^"']*)["']""").find(script) ?: return false
        ZenDeliveryEngine.deliverSmartLink(sourceName, "Vidmoly", match.groupValues[1], referer, quality, callback)
        return true
    }
    
    private suspend fun extractLuluvid(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(url, referer = referer ?: url).document.outerHtml()
        val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html) ?: return false
        val decoded = ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
        val videoUrl = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(decoded)?.groupValues?.get(1) ?: return false
        ZenDeliveryEngine.deliverSmartLink(sourceName, "Luluvid", videoUrl.replace("\\/", "/"), referer, quality, callback)
        return true
    }
    
    private suspend fun extractStreamHG(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = app.get(url, referer = referer).text
        val packed = ZenCryptoAndObfuscation.findPackedJsInPage(text) ?: return false
        val unpacked = ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
        val m3u8 = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(unpacked)?.value ?: return false
        ZenDeliveryEngine.deliverSmartLink(sourceName, "StreamHG", m3u8, referer, quality, callback)
        return true
    }
    
    private suspend fun extractVidguard(
        url: String, referer: String?, sourceName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = if (url.contains("/d/") || url.contains("/v/")) {
            url.replace("/d/", "/e/").replace("/v/", "/e/")
        } else url
        
        val script = app.get(embedUrl, referer = referer ?: "https://vidguard.to/")
            .document.selectFirst("script:containsData(eval)")?.data() ?: return false
        
        val streamMatch = Regex("""stream\s*:\s*["']([^"']+)["']""").find(script)
        if (streamMatch != null) {
            ZenDeliveryEngine.deliverSmartLink(
                sourceName, "Vidguard",
                ZenCryptoAndObfuscation.sigDecode(streamMatch.groupValues[1]),
                referer, quality, callback
            )
            return true
        }
        return false
    }
}
