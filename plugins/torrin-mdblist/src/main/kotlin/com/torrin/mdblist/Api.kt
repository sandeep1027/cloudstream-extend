package com.torrin.mdblist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** IMDB public suggestion API — https://v2.sg.media-imdb.com/suggestion/{letter}/{query}.json */
@Serializable
data class ImdbSuggestResponse(
    val d: List<ImdbSuggest> = emptyList()
)

@Serializable
data class ImdbSuggest(
    val id: String? = null,   // "tt0816692"
    val l: String? = null,    // label / title
    val y: String? = null,    // year (string in the API)
    val qid: String? = null,  // "title" (movie) | "tvSeries" | "videoGame" | ...
    val s: String? = null,    // movies: synopsis; tv: cast
    val q: String? = null,    // "2014 • Christopher Nolan, ..."
    val i: ImdbImage? = null
)

@Serializable
data class ImdbImage(
    val imageUrl: String? = null
)

/**
 * Torrentio public instance — https://torrentio.strem.fun/stream/{movie|series}/{imdbId}.json
 *
 * Quality/size/seeders are not top-level fields; they are packed into the
 * `name` label, the multi-line `title` string (with 👤/💾/⚙️ markers) and
 * `behaviorHints.bingeGroup`.
 */
@Serializable
data class TorrentioResponse(
    val streams: List<TorrentioStream> = emptyList()
)

@Serializable
data class TorrentioStream(
    val name: String? = null,     // "Torrentio\n4k DV | HDR10+"
    val title: String? = null,    // "Release\nPerFile.mkv\n👤 238 💾 27.63 GB ⚙️ Source"
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val behaviorHints: TorrentioHints? = null
)

@Serializable
data class TorrentioHints(
    val bingeGroup: String? = null,
    val filename: String? = null
)

/**
 * TMDB — https://developer.themoviedb.org
 *
 * The plugin resolves titles by their IMDb id (tt...). TMDB's Find API maps an
 * IMDb id to a TMDB id (+ cast), and the Details API returns the plot
 * (overview), poster and release date. Two calls per title, but the metadata
 * is authoritative where IMDB's public suggestion list is sparse.
 */
@Serializable
data class TmdbFindResponse(
    val movie_results: List<TmdbResult> = emptyList(),
    val tv_results: List<TmdbResult> = emptyList()
)

@Serializable
data class TmdbResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val release_date: String? = null
)

@Serializable
data class TmdbMedia(
    val overview: String? = null,
    val title: String? = null,
    val name: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val poster_path: String? = null
)

/** TMDB External IDs — maps a TMDB id to its IMDb id (tt...). */
@Serializable
data class TmdbExternalIdsResponse(
    val imdb_id: String? = null
)

/**
 * MDBList — https://mdblist.com (user supplied free API key).
 *
 * `GET /catalog/movie|show?apikey=***&sort=released&sort_order=desc&append_to_response=poster,description`
 *
 * The item schema is not fully pinned in the public docs, so every field is
 * optional and parsed defensively. Cross-reference ids arrive under
 * `sources` — e.g. `sources.imdb.imdbId` — and may occasionally come back as
 * a bare string, so `sources` is kept as a raw [JsonElement] and ids are
 * extracted with [imdbIdOf] / [tmdbIdOf].
 */
@Serializable
data class MdblistCatalog(
    val movies: List<MdblistItem> = emptyList(),
    val shows: List<MdblistItem> = emptyList()
)

@Serializable
data class MdblistItem(
    val title: String? = null,
    val year: Int? = null,
    val released: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val sources: JsonElement? = null
)

/**
 * Extracts the IMDb id (tt...) from a catalog item's raw `sources` element.
 * Handles both `{"imdb": {"imdbId": "tt..."}}` and `{"imdb": "tt..."}`.
 */
fun imdbIdOf(item: MdblistItem): String? {
    val imdb = (item.sources as? JsonObject)?.get("imdb") ?: return null
    val id = when (imdb) {
        is JsonPrimitive -> if (imdb.isString) imdb.content else null
        is JsonObject -> (imdb["imdbId"] as? JsonPrimitive)?.content
        else -> null
    }
    return id?.takeIf { it.startsWith("tt") }
}

/** Extracts the TMDB id from a catalog item's raw `sources` element, if present. */
fun tmdbIdOf(item: MdblistItem): Int? {
    val tmdb = (item.sources as? JsonObject)?.get("tmdb") ?: return null
    return when (tmdb) {
        is JsonPrimitive -> tmdb.content.toIntOrNull()
        is JsonObject -> (tmdb["tmdbId"] as? JsonPrimitive)?.content?.toIntOrNull()
        else -> null
    }
}
