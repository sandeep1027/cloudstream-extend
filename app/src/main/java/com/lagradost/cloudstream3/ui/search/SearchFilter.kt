package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse

/**
 * Sorting modes for search results.
 *
 * Stored as the enum name in preferences, so keep the names stable.
 */
enum class SearchSortMode {
    /** No sorting — results appear in the order returned by the providers. */
    DEFAULT,

    /** Alphabetical by title, A → Z. */
    NAME_ASC,

    /** Alphabetical by title, Z → A. */
    NAME_DESC,

    /** Release year, newest first. */
    YEAR_DESC,

    /** Release year, oldest first. */
    YEAR_ASC,

    /** Rating, highest first. */
    RATING_DESC,

    /** Rating, lowest first. */
    RATING_ASC,
}

/**
 * Represents the advanced filters a user can apply to search results.
 *
 * All fields are optional; a null/empty value means "no filter" for that dimension.
 *
 * @property excludedQualities qualities to hide (matches the existing settings UI semantics).
 * @property yearMin minimum release year (inclusive); null means no lower bound.
 * @property yearMax maximum release year (inclusive); null means no upper bound.
 * @property sortMode ordering applied after filtering.
 */
data class SearchFilter(
    val excludedQualities: Set<SearchQuality> = emptySet(),
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val sortMode: SearchSortMode = SearchSortMode.DEFAULT,
) {
    /** True if no filter of any kind is active. */
    val isEmpty: Boolean
        get() = excludedQualities.isEmpty() &&
            yearMin == null &&
            yearMax == null &&
            sortMode == SearchSortMode.DEFAULT
}

/** Extract the release year from a [SearchResponse], if available. */
fun SearchResponse.releaseYear(): Int? = when (this) {
    is MovieSearchResponse -> year
    is TvSeriesSearchResponse -> year
    is AnimeSearchResponse -> year
    else -> null
}

/**
 * Apply this [SearchFilter] to a list of [SearchResponse].
 *
 * Filtering happens first (quality exclusion + year range), then the surviving
 * items are sorted according to [SearchFilter.sortMode]. Items missing the
 * sort attribute (e.g. no year, no rating) sink to the bottom rather than
 * being dropped.
 */
fun List<SearchResponse>.applySearchFilter(filter: SearchFilter): List<SearchResponse> {
    if (filter.isEmpty) return this

    val filtered = this.filter { item ->
        val quality = item.quality
        if (quality != null && filter.excludedQualities.contains(quality)) return@filter false

        val year = item.releaseYear()
        if (filter.yearMin != null && (year == null || year < filter.yearMin)) return@filter false
        if (filter.yearMax != null && (year == null || year > filter.yearMax)) return@filter false

        true
    }

    return when (filter.sortMode) {
        SearchSortMode.DEFAULT -> filtered
        SearchSortMode.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
        SearchSortMode.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
        SearchSortMode.YEAR_DESC -> filtered.sortedWith(
            compareByDescending<SearchResponse> { it.releaseYear() ?: Int.MIN_VALUE }
                .thenBy { it.name.lowercase() }
        )
        SearchSortMode.YEAR_ASC -> filtered.sortedWith(
            compareBy<SearchResponse> { it.releaseYear() ?: Int.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
        SearchSortMode.RATING_DESC -> filtered.sortedWith(
            compareByDescending<SearchResponse> { it.score?.toDouble(10) ?: -1.0 }
                .thenBy { it.name.lowercase() }
        )
        SearchSortMode.RATING_ASC -> filtered.sortedWith(
            compareBy<SearchResponse> { it.score?.toDouble(10) ?: Double.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
    }
}
