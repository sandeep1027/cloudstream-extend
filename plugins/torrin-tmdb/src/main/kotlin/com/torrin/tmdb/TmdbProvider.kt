package com.torrin.tmdb

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.metaproviders.tmdbApiKeyOverride
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "Torrin TMDB" — latest-releases dashboard sourced from TMDB's Discover API.
 *
 * Why TMDB (and not MDBList): TMDB's rate limits are per-second, not a small
 * "unique catalog queries per week" cap, so these rows refresh daily without
 * the freeze/quota-exhaustion the MDBList tier hit. Two latest rows per media
 * type: India-only and Global. Every title resolves to magnet links via
 * Torrentio and plays through the app-side Torrin/TorBox debrid layer.
 *
 * Trending rows are the shared curated list; playback, search, deep-linking and
 * metadata resolution are the same engine as the base "Torrin" extension.
 */
class TmdbProvider : MainAPI() {

    override var name = "Torrin TMDB"
    override var mainUrl = "https://tmdb.torrin.app"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val supportedSyncNames = setOf(SyncIdName.Imdb)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    override val mainPage = listOf(
        MainPageData("Trending Movies", DATA_MOVIES),
        MainPageData("Trending TV Shows", DATA_TV),
        MainPageData("Latest Movies (India)", DATA_LATEST_MOVIES_IN),
        MainPageData("Latest Shows (India)", DATA_LATEST_SHOWS_IN),
        MainPageData("Latest Movies (Global)", DATA_LATEST_MOVIES_GL),
        MainPageData("Latest Shows (Global)", DATA_LATEST_SHOWS_GL)
    )

    // ----------------------------------------------------------------- pages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items: List<SearchResponse> = when (request.data) {
            DATA_MOVIES -> Curated.MOVIES.map { it.toSearchResult(this) }
            DATA_TV -> Curated.TV.map { it.toSearchResult(this) }
            DATA_LATEST_MOVIES_IN -> latestFromTmdb("movie", SCOPE_IN).map { it.toSearchResult(this) }
            DATA_LATEST_SHOWS_IN -> latestFromTmdb("tv", SCOPE_IN).map { it.toSearchResult(this) }
            DATA_LATEST_MOVIES_GL -> latestFromTmdb("movie", SCOPE_GLOBAL).map { it.toSearchResult(this) }
            DATA_LATEST_SHOWS_GL -> latestFromTmdb("tv", SCOPE_GLOBAL).map { it.toSearchResult(this) }
            else -> return null
        }
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items)
    }

    // ------------------------------------------- latest rows (TMDB discover)
    //
    // All four latest rows (movie/tv × India/Global) are built in one pass and
    // cached in memory for CACHE_TTL, so when the home page renders only the
    // first row pays the four Discover calls.

    private data class LatestItem(
        val tmdbId: Int,
        val type: String, // "movie" | "tv"
        val name: String,
        val year: Int?,
        val poster: String?
    )

    /** Pair of (expiry timestamp, rows keyed by "kind/scope"). */
    @Volatile
    private var tmdbLatestCache: Pair<Long, Map<String, List<LatestItem>>>? = null

    private val tmdbLatestBuildLock = Mutex()

    private fun keyOf(kind: String, scope: String) = "$kind/$scope"

    private suspend fun latestFromTmdb(kind: String, scope: String): List<LatestItem> {
        val key = keyOf(kind, scope)
        val cached = tmdbLatestCache
        if (cached != null && System.currentTimeMillis() < cached.first) {
            return cached.second[key].orEmpty()
        }
        return tmdbLatestBuildLock.withLock {
            val fresh = tmdbLatestCache
            if (fresh != null && System.currentTimeMillis() < fresh.first) {
                fresh.second[key].orEmpty()
            } else {
                val rows = buildLatestRows()
                // An all-empty result is most likely a transient TMDB/network
                // hiccup: cache it briefly so it self-heals quickly.
                val ttl = if (rows.values.any { it.isNotEmpty() }) CACHE_TTL else CACHE_TTL_EMPTY
                tmdbLatestCache = Pair(System.currentTimeMillis() + ttl, rows)
                rows[key].orEmpty()
            }
        }
    }

    /** Build all four latest rows (movie/tv × India/Global) in one pass. */
    private suspend fun buildLatestRows(): Map<String, List<LatestItem>> {
        val today = LocalDate.now(ZoneOffset.UTC).toString()

        suspend fun build(kind: String, scope: String): List<LatestItem> {
            val items = ArrayList<LatestItem>()
            val seen = HashSet<Int>()
            // Global benefits from a second page for a fuller pool.
            val pages = if (scope == SCOPE_GLOBAL) 2 else 1
            for (page in 1..pages) {
                if (items.size >= MAX_ROW_SIZE) break
                val results = runCatching { fetchTmdbDiscover(kind, scope, page, today) }
                    .getOrNull()?.results.orEmpty()
                for (r in results) {
                    if (items.size >= MAX_ROW_SIZE) break
                    if (!seen.add(r.id)) continue
                    val title = (if (kind == "tv") r.name else r.title)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    val date = if (kind == "tv") r.first_air_date else r.release_date
                    items.add(
                        LatestItem(
                            r.id, kind, title,
                            date?.substringBefore('-')?.toIntOrNull(),
                            posterUrl(r.poster_path)
                        )
                    )
                }
            }
            return items.take(MAX_ROW_SIZE)
        }

        return mapOf(
            keyOf("movie", SCOPE_IN) to build("movie", SCOPE_IN),
            keyOf("tv", SCOPE_IN) to build("tv", SCOPE_IN),
            keyOf("movie", SCOPE_GLOBAL) to build("movie", SCOPE_GLOBAL),
            keyOf("tv", SCOPE_GLOBAL) to build("tv", SCOPE_GLOBAL)
        )
    }

    private fun posterUrl(path: String?): String? =
        path?.takeIf { it.startsWith("/") }?.let { "$TMDB_IMAGE_BASE$it" }

    /** TMDB-sourced dashboard card -> TMDB content url (resolved to IMDb in load). */
    private fun LatestItem.toSearchResult(api: MainAPI): SearchResponse {
        val url = "$mainUrl/tm/$type/$tmdbId/${encodePath(name)}"
        return if (type == "tv") {
            api.newTvSeriesSearchResponse(name, url) {
                posterUrl = poster
                year = year
            }
        } else {
            api.newMovieSearchResponse(name, url) {
                posterUrl = poster
                year = year
            }
        }
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val letter = q.first().lowercaseChar().toString()
        val encoded = encodePath(q)
        val response = imdbSuggest(letter, encoded) ?: return null
        val seen = HashSet<String>()
        return response.d.asSequence()
            .filter {
                it.id?.startsWith("tt") == true &&
                    (it.qid == "title" || it.qid == "tvSeries")
            }
            .take(MAX_SEARCH_RESULTS)
            .mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                val title = item.l?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                if (item.qid == "tvSeries") {
                    newTvSeriesSearchResponse(title, contentUrl(id, title)) {
                        posterUrl = item.i?.imageUrl
                        year = item.y?.toIntOrNull()
                    }
                } else {
                    newMovieSearchResponse(title, contentUrl(id, title)) {
                        posterUrl = item.i?.imageUrl
                        year = item.y?.toIntOrNull()
                    }
                }
            }
            .toList()
    }

    // ----------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val parsed = parseUrl(url) ?: return null
        var tt = parsed.first
        val nameHint = parsed.second

        // "tm:movie/12345" (TMDB-sourced dashboard card) -> resolve its IMDb id
        // first; everything downstream (Torrentio) is IMDb-id based.
        if (tt.startsWith("tm:")) {
            val tmType = tt.removePrefix("tm:").substringBefore('/')
            val tmId = tt.substringAfter('/', "").toIntOrNull() ?: return null
            tt = fetchTmdbImdbId(tmType, tmId) ?: return null
        }

        // Probe the movie endpoint first; if it returns per-episode files the
        // id is a series, so fetch the dedicated series endpoint.
        val movieStreams = runCatching { fetchTorrentio("movie", tt) }.getOrNull()
        val isSeries = movieStreams?.any { looksLikeEpisode(it) } == true
        val seriesStreams =
            if (isSeries) runCatching { fetchTorrentio("series", tt) }.getOrNull() else null

        val meta = resolveMeta(tt, nameHint, movieStreams, seriesStreams)

        if (isSeries) {
            val episodes = buildEpisodes(seriesStreams ?: movieStreams.orEmpty(), meta)
            return newTvSeriesLoadResponse(meta.name, url, TvType.TvSeries, episodes) {
                posterUrl = meta.poster
                backgroundPosterUrl = meta.poster
                year = meta.year
                plot = meta.synopsis
                addImdbId(tt)
            }
        }

        return newMovieLoadResponse(meta.name, url, TvType.Movie, "m:$tt") {
            posterUrl = meta.poster
            backgroundPosterUrl = meta.poster
            year = meta.year
            plot = meta.synopsis
            addImdbId(tt)
        }
    }

    // ------------------------------------------------------------ loadLinks
    //
    // Every quality tier (movie) and every episode (series) is emitted once
    // per debrid: a "Torrin" entry and a "TorBox" entry, each carrying a
    // cs_debrid hint. The player tries the hinted debrid first and falls back
    // to the other enabled one, so with both enabled the user gets an explicit
    // choice; with only one enabled the other entry resolves through it.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("m:")) {
            // Full title -> resolve the best magnet per quality tier.
            val tt = data.removePrefix("m:")
            val streams = runCatching { fetchTorrentio("movie", tt) }.getOrNull().orEmpty()
            val bestPerQuality = LinkedHashMap<String, TorrentioStream>()
            for (s in streams) {
                if (s.infoHash.isNullOrBlank()) continue
                val label = qualityLabel(s)
                val current = bestPerQuality[label]
                if (current == null || seeders(s) > seeders(current)) {
                    bestPerQuality[label] = s
                }
            }
            var emitted = 0
            for ((label, s) in bestPerQuality.entries
                .sortedByDescending { (label, s) -> qualityValue(label) * 100_000L + seeders(s) }
            ) {
                for (debrid in DEBRIDS) {
                    if (emitted >= MAX_MOVIE_LINKS) return true
                    callback(magnetLink(s, label, debrid))
                    emitted++
                }
            }
            return emitted > 0
        }

        // Episode data: "infoHash|fileIdx|quality|size|seeders|source"
        val parts = data.split("|")
        val infoHash = parts.getOrNull(0).orEmpty()
        val fileIdx = parts.getOrNull(1)?.toIntOrNull()
        val label = parts.getOrNull(2).orEmpty()
        val size = parts.getOrNull(3).orEmpty()
        val seedCount = parts.getOrNull(4).orEmpty()
        val source = parts.getOrNull(5).orEmpty()
        if (infoHash.length !in 32..64) return false
        val details = listOfNotNull(
            label.takeIf { it.isNotBlank() },
            size.takeIf { it.isNotBlank() },
            seedCount.takeIf { it.isNotBlank() && it != "0" }?.let { "$it seeders" },
            source.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
        for (debrid in DEBRIDS) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "• $debrid • ${details.ifBlank { "Torrent stream" }}",
                    url = magnet(infoHash, fileIdx, debrid),
                    type = ExtractorLinkType.MAGNET
                ) {
                    quality = qualityValue(label)
                }
            )
        }
        return true
    }

    // --------------------------------------------------------- deep linking

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        if (name != SyncIdName.Imdb || !id.startsWith("tt")) return null
        return "$mainUrl/$id"
    }

    // -------------------------------------------------------------- helpers

    private data class Meta(
        val name: String,
        val year: Int?,
        val poster: String?,
        val synopsis: String?
    )

    private fun CuratedItem.toSearchResult(api: MainAPI): SearchResponse {
        val item = this
        return if (item.type == TYPE_TV) {
            api.newTvSeriesSearchResponse(item.name, contentUrl(item.id, item.name)) {
                posterUrl = item.poster
                year = item.year
            }
        } else {
            api.newMovieSearchResponse(item.name, contentUrl(item.id, item.name)) {
                posterUrl = item.poster
                year = item.year
            }
        }
    }

    /** Absolute content url: {mainUrl}/{tt}/{title} */
    private fun contentUrl(id: String, title: String): String =
        "$mainUrl/$id/${encodePath(title)}"

    /**
     * Extracts the id key and (optionally) the title back out of a content url.
     * Returns null when the url does not point at a known title.
     *
     * Two shapes:
     *  - `{host}/{tt}/{title}`              -> ("tt...", title)
     *  - `{host}/tm/{type}/{id}/{title}`    -> ("tm:{type}/{id}", title)
     */
    private fun parseUrl(url: String): Pair<String, String?>? {
        val tail = url.substringAfter("torrin.app/", url)
        val parts = tail.split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() == "tm" && parts.size >= 3 && parts[2].toIntOrNull() != null) {
            val key = "tm:${parts[1]}/${parts[2]}"
            val encoded = parts.drop(3).joinToString("/")
            return key to decodeEncoded(encoded)
        }
        val tt = parts.firstOrNull { it.startsWith("tt") } ?: return null
        val encoded = parts.drop(1).joinToString("/")
        return tt to decodeEncoded(encoded)
    }

    private fun decodeEncoded(encoded: String): String? =
        if (encoded.isEmpty()) null
        else runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: encoded

    private suspend fun imdbSuggest(letter: String, encodedQuery: String): ImdbSuggestResponse? {
        val response = runCatching {
            app.get(
                url = IMDB_SUGGEST.format(letter, encodedQuery),
                headers = HEADERS
            )
        }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<ImdbSuggestResponse>(response.text) }
            .getOrNull()
    }

    private suspend fun fetchTorrentio(kind: String, tt: String): List<TorrentioStream> {
        val response = runCatching {
            app.get(
                url = TORRENTIO_STREAM.format(kind, tt),
                headers = HEADERS
            )
        }.getOrNull()
        if (response == null || !response.isSuccessful) return emptyList()
        return runCatching { AppUtils.parseJson<TorrentioResponse>(response.text) }
            .getOrNull()?.streams
            .orEmpty()
    }

    private suspend fun fetchTmdbFind(tt: String): TmdbFindResponse? {
        // Maps an IMDb id to a TMDB id (+ cast). external_source=imdb_id.
        val url = "$TMDB_BASE/find/$tt?api_key=$TMDB_API_KEY&external_source=imdb_id"
        val response = runCatching { app.get(url = url, headers = HEADERS) }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<TmdbFindResponse>(response.text) }.getOrNull()
    }

    private suspend fun fetchTmdbMedia(kind: String, id: Int): TmdbMedia? {
        // kind = "movie" | "tv". Returns overview, poster_path, release date.
        val url = "$TMDB_BASE/$kind/$id?api_key=$TMDB_API_KEY"
        val response = runCatching { app.get(url = url, headers = HEADERS) }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<TmdbMedia>(response.text) }.getOrNull()
    }

    /**
     * Latest RELEASED titles, date desc. India or Global scope.
     *
     * `$dateField.lte=$today` keeps only titles that have actually released
     * (without it, sort-by-date desc lists upcoming titles first). TMDB's
     * per-second rate limits make the daily-changing date value a non-issue.
     */
    private suspend fun fetchTmdbDiscover(
        kind: String,
        scope: String,
        page: Int,
        today: String
    ): TmdbDiscoverResponse {
        val dateField = if (kind == "tv") "first_air_date" else "primary_release_date"
        val scopeFilter = if (scope == SCOPE_IN) "&region=IN&with_origin_country=IN" else ""
        val url = "$TMDB_BASE/discover/$kind?api_key=$TMDB_API_KEY" +
            "&language=en-US$scopeFilter&sort_by=$dateField.desc" +
            "&$dateField.lte=$today&vote_count.gte=10&page=$page"
        val response = runCatching { app.get(url = url, headers = HEADERS) }.getOrNull()
        if (response == null || !response.isSuccessful) return TmdbDiscoverResponse()
        return runCatching { AppUtils.parseJson<TmdbDiscoverResponse>(response.text) }.getOrNull()
            ?: TmdbDiscoverResponse()
    }

    /** TMDB id -> IMDb id (tt...). */
    private suspend fun fetchTmdbImdbId(kind: String, id: Int): String? {
        val url = "$TMDB_BASE/$kind/$id/external_ids?api_key=$TMDB_API_KEY"
        val response = runCatching { app.get(url = url, headers = HEADERS) }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<TmdbExternalIdsResponse>(response.text) }.getOrNull()
            ?.imdb_id?.takeIf { it.startsWith("tt") }
    }

    /**
     * Metadata for a title. Prefers TMDB (authoritative plot/poster/year,
     * resolved from the IMDb id); falls back to the IMDB suggestion entry when
     * the url carried the title, then to the torrent catalog's own title line.
     */
    private suspend fun resolveMeta(
        tt: String,
        nameHint: String?,
        movieStreams: List<TorrentioStream>?,
        seriesStreams: List<TorrentioStream>?
    ): Meta {
        // 1. TMDB — plot (overview), poster, year. Two calls (Find → Details).
        val find = runCatching { fetchTmdbFind(tt) }.getOrNull()
        val tmdbId = when {
            seriesStreams != null -> find?.tv_results?.firstOrNull()
            find?.tv_results?.isNotEmpty() == true && find.movie_results.isEmpty() -> find.tv_results.firstOrNull()
            else -> find?.movie_results?.firstOrNull()
        }
        if (tmdbId != null) {
            val isSeries = seriesStreams != null ||
                (find?.tv_results?.isNotEmpty() == true && find.movie_results.isEmpty())
            val media = runCatching {
                fetchTmdbMedia(if (isSeries) "tv" else "movie", tmdbId.id)
            }.getOrNull()
            val title = media?.title ?: media?.name
            val yearRaw = media?.release_date ?: media?.first_air_date
            val poster = media?.poster_path?.let { if (it.startsWith("/")) "$TMDB_IMAGE_BASE$it" else it }
            if (!title.isNullOrBlank()) {
                return Meta(
                    name = title.trim(),
                    year = yearRaw?.substringBefore('-')?.toIntOrNull(),
                    poster = poster,
                    synopsis = media?.overview?.takeIf { it.isNotBlank() }
                )
            }
        }

        // 2. IMDB suggestion — poster, year, synopsis, when the url carried the
        //    title (cheap single call).
        if (!nameHint.isNullOrBlank()) {
            val letter = nameHint.trim().first().lowercaseChar().toString()
            val match = imdbSuggest(letter, encodePath(nameHint.trim()))
                ?.d?.firstOrNull { it.id == tt }
            if (match != null && !match.l.isNullOrBlank()) {
                return Meta(
                    name = match.l.trim(),
                    year = match.y?.toIntOrNull(),
                    poster = match.i?.imageUrl,
                    synopsis = if (match.qid == "title") match.s?.takeIf { it.isNotBlank() } else null
                )
            }
        }

        // 3. Torrent catalog's own title line.
        val first = (movieStreams ?: seriesStreams)?.firstOrNull()
        val fallbackTitle = first?.title?.lineSequence()?.firstOrNull()?.trim()
        return Meta(fallbackTitle ?: "Torrent $tt", null, null, null)
    }

    private fun looksLikeEpisode(stream: TorrentioStream): Boolean =
        EPISODE_PATTERN.containsMatchIn(perFileLine(stream)) ||
            EPISODE_PATTERN.containsMatchIn(stream.behaviorHints?.filename.orEmpty())

    /**
     * One row per (season, episode), keeping the best available release
     * (resolution first, then seeders). Multi-file season packs are expanded
     * through their per-file names.
     */
    private fun buildEpisodes(
        streams: List<TorrentioStream>,
        meta: Meta
    ): List<Episode> {
        data class Candidate(val stream: TorrentioStream, val rank: Long)

        val best = HashMap<String, Candidate>()
        val order = ArrayList<String>()
        for (s in streams) {
            if (s.infoHash.isNullOrBlank()) continue
            val source = episodeSourceLine(s) ?: continue
            val match = EPISODE_PATTERN.find(source) ?: continue
            val season = match.groupValues[1].toInt()
            val episode = match.groupValues[2].toInt()
            val key = "$season:$episode"
            val rank = qualityValue(qualityLabel(s)).toLong() * 100_000 + seeders(s)
            val current = best[key]
            if (current == null || rank > current.rank) {
                if (current == null) order.add(key)
                best[key] = Candidate(s, rank)
            }
        }

        return order.mapNotNull { key ->
            val (seasonNum, episodeNum) = key.split(":").map { it.toInt() }
            val candidate = best[key] ?: return@mapNotNull null
            val s = candidate.stream
            val perFile = perFileLine(s)
            val raw = if (EPISODE_PATTERN.containsMatchIn(perFile)) perFile else
                s.behaviorHints?.filename.orEmpty()
            val details = listOfNotNull(
                qualityLabel(s).takeIf { it.isNotBlank() },
                sizeOf(s).takeIf { it.isNotBlank() },
                seeders(s).takeIf { it > 0 }?.toString()?.let { "$it seeders" },
                sourceOf(s).takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            newEpisode(episodeData(s), {
                name = cleanEpisodeName(raw, meta.name)
                season = seasonNum
                episode = episodeNum
                posterUrl = meta.poster
                description = details
            }, fix = false)
        }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .take(MAX_EPISODES)
    }

    private fun episodeData(s: TorrentioStream): String = listOf(
        s.infoHash.orEmpty(),
        (s.fileIdx ?: 0).toString(),
        qualityLabel(s),
        sizeOf(s),
        seeders(s).toString(),
        sourceOf(s)
    ).joinToString("|")

    private suspend fun magnetLink(s: TorrentioStream, label: String, debrid: String): ExtractorLink {
        val details = listOfNotNull(
            label.takeIf { it.isNotBlank() },
            sizeOf(s).takeIf { it.isNotBlank() },
            seeders(s).takeIf { it > 0 }?.toString()?.let { "$it seeders" },
            sourceOf(s).takeIf { it.isNotBlank() }
        ).joinToString(" • ")
        return newExtractorLink(
            source = name,
            name = "• $debrid • ${details.ifBlank { "Torrent stream" }}",
            url = magnet(s.infoHash.orEmpty(), s.fileIdx, debrid),
            type = ExtractorLinkType.MAGNET
        ) {
            quality = qualityValue(label)
        }
    }

    /**
     * Magnet with client-side hints:
     *  - cs_file=<idx>   file pick for multi-file releases (episode selection)
     *  - cs_debrid=<n>   which debrid the user chose (Torrin / TorBox)
     * Both are stripped by the debrid clients before submission.
     */
    private fun magnet(infoHash: String, fileIdx: Int?, debrid: String): String =
        "magnet:?xt=urn:btih:$infoHash" +
            (fileIdx?.let { "&cs_file=$it" } ?: "") +
            "&cs_debrid=$debrid"

    /** Second line of torrentio's `name` field: "4k DV | HDR10+", "1080p HEVC", ... */
    private fun qualityLabel(s: TorrentioStream): String {
        val label = s.name?.lineSequence()?.drop(1)?.firstOrNull()?.trim().orEmpty()
        if (label.isNotEmpty()) return label
        val file = s.behaviorHints?.filename.orEmpty()
        val match = RESOLUTION_PATTERN.find(file)
        return match?.groupValues?.get(1)?.lowercase() ?: ""
    }

    /** Map a quality label to a player resolution value (2160/1080/...). */
    private fun qualityValue(label: String): Int {
        if (label.contains("4k", ignoreCase = true) || label.contains("2160", ignoreCase = true)) {
            return 2160
        }
        val match = RESOLUTION_PATTERN.find(label)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun seeders(s: TorrentioStream): Int =
        SEEDERS_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun sizeOf(s: TorrentioStream): String =
        SIZE_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1).orEmpty()

    private fun sourceOf(s: TorrentioStream): String =
        SOURCE_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1)?.trim().orEmpty()

    /** Per-file name inside a multi-line torrentio title, when present. */
    private fun perFileLine(s: TorrentioStream): String {
        val lines = s.title?.lineSequence()?.toList().orEmpty()
        return lines.getOrNull(1)?.trim().orEmpty()
    }

    /** The line an episode number should be parsed from. */
    private fun episodeSourceLine(s: TorrentioStream): String? {
        val perFile = perFileLine(s)
        if (EPISODE_PATTERN.containsMatchIn(perFile)) return perFile
        val file = s.behaviorHints?.filename
        if (!file.isNullOrBlank() && EPISODE_PATTERN.containsMatchIn(file)) return file
        return null
    }

    private fun cleanEpisodeName(raw: String, showName: String): String {
        var out = EXTENSION_PATTERN.replace(raw.trim(), "")
        if (showName.isNotBlank()) {
            out = out.replace(
                Regex("(?i)^${Regex.escape(showName)}\\s+"),
                ""
            )
        }
        return out.replace(WHITESPACE_PATTERN, " ").trim()
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val DATA_MOVIES = "trending-movies"
        const val DATA_TV = "trending-tv"
        const val DATA_LATEST_MOVIES_IN = "tmdb-latest-movies-in"
        const val DATA_LATEST_SHOWS_IN = "tmdb-latest-shows-in"
        const val DATA_LATEST_MOVIES_GL = "tmdb-latest-movies-global"
        const val DATA_LATEST_SHOWS_GL = "tmdb-latest-shows-global"

        /** Discover scope filters. */
        const val SCOPE_IN = "IN"
        const val SCOPE_GLOBAL = "GLOBAL"

        /** Latest rows live this long in memory before re-fetching. */
        const val CACHE_TTL = 6 * 60 * 60 * 1000L // 6 h
        /** Failed/empty builds are retried after this. */
        const val CACHE_TTL_EMPTY = 10 * 60 * 1000L // 10 min
        const val MAX_ROW_SIZE = 24
        const val TYPE_TV = "TvSeries"

        const val IMDB_SUGGEST = "https://v2.sg.media-imdb.com/suggestion/%s/%s.json"
        const val TORRENTIO_STREAM = "https://torrentio.strem.fun/stream/%s/%s.json"

        // TMDB — metadata (plot/poster/year) + latest-releases source. Uses
        // the user supplied key from settings (Settings -> Player -> Metadata)
        // when present, else the built-in key.
        const val DEFAULT_TMDB_API_KEY = "9f80b1a1a0112b04448d986f35313bbc"
        val TMDB_API_KEY: String
            get() = tmdbApiKeyOverride ?: DEFAULT_TMDB_API_KEY
        const val TMDB_BASE = "https://api.themoviedb.org/3"
        const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

        const val MAX_SEARCH_RESULTS = 15
        const val MAX_MOVIE_LINKS = 12
        const val MAX_EPISODES = 500

        /** Debrid variants emitted per quality tier / episode. */
        val DEBRIDS = listOf("Torrin", "TorBox")

        val HEADERS = mapOf("User-Agent" to "Mozilla/5.0")

        val EPISODE_PATTERN = Regex("S(\\d{1,2})E(\\d{1,4})")
        val RESOLUTION_PATTERN = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
        val SEEDERS_PATTERN = Regex("👤\\s*(\\d+)")
        val SIZE_PATTERN = Regex("💾\\s*([\\d.,]+\\s*\\w+)")
        val SOURCE_PATTERN = Regex("⚙️?\\s*([^\\n|]+)$")
        val EXTENSION_PATTERN =
            Regex("\\.(mkv|mp4|avi|mov|m4v|webm|mpg|mpeg|ts|m2ts)$", RegexOption.IGNORE_CASE)
        val WHITESPACE_PATTERN = Regex("\\s+")
    }
}
