package com.strata.tv.data.repo

import androidx.room.withTransaction
import com.strata.tv.data.db.AppDatabase
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ChannelEntity
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.data.m3u.M3uEntry
import com.strata.tv.data.settings.ProviderConfig
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.xtream.XtreamJsonClient
import com.strata.tv.data.xtream.XtreamSnapshot
import com.strata.tv.domain.ChannelCategorizer
import com.strata.tv.domain.ChannelDeduplicator
import com.strata.tv.domain.ContentIdHasher
import com.strata.tv.domain.ForeignContentFilter
import com.strata.tv.domain.SkyChannelNumbers
import com.strata.tv.domain.TitleParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports live channels, movies and series metadata from Xtream JSON endpoints,
 * then classifies, deduplicates and persists the catalogue in Room.
 * Progress is exposed as a StateFlow so the UI can show sync status.
 */
@Singleton
class SyncService @Inject constructor(
    private val db: AppDatabase,
    private val contentDao: ContentDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val progressTracker: com.strata.tv.data.tmdb.EnrichmentProgressTracker,
    private val settingsRepo: SettingsRepository,
    private val xtreamJson: XtreamJsonClient,
    private val libraryFilter: LibraryFilterRepository,
) {

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val bytesRead: Long) : Progress
        data class Parsing(val parsed: Int, val skipped: Int) : Progress
        data object PostProcessing : Progress
        data class Done(val totalParsed: Int, val totalSkipped: Int) : Progress
        data class Error(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Called by [com.strata.tv.ui.home.HomeViewModel] when it decides
     * no fresh sync is needed (returning user, sync frequency cap not
     * yet reached).  Without this, [progress] would stay [Progress.Idle]
     * and the gate that's now wired to wait for sync + enrichment would
     * never transition to Main.
     */
    fun markSkipped() {
        _progress.value = Progress.Done(totalParsed = 0, totalSkipped = 0)
    }

    /** Synchronize the provider catalogue exclusively through Xtream Codes JSON API. */
    suspend fun syncFromXtream(provider: ProviderConfig, sourceId: Int) {
        try {
            if (!provider.isConfigured) error("Xtream provider is not configured")
            _progress.value = Progress.Downloading(0)
            val settings = settingsRepo.current()
            val excludedCategories = settings.excludedCategories.map { it.lowercase() }.toSet()
            val countryWhitelist = settings.countryWhitelist.map { it.uppercase() }.toSet()
            fun isExcludedGroup(groupTitle: String): Boolean {
                if (countryWhitelist.isNotEmpty() && groupTitle.contains('|')) {
                    val prefix = groupTitle.substringBefore('|').trim().uppercase()
                    if (prefix.isNotEmpty() && prefix !in countryWhitelist) return true
                }
                if (ForeignContentFilter.shouldExclude(groupTitle, countryWhitelist)) return true
                if (excludedCategories.isNotEmpty()) {
                    val group = groupTitle.lowercase()
                    if (excludedCategories.any { needle -> group.contains(needle) }) return true
                }
                return false
            }
            fun isExcluded(entry: M3uEntry): Boolean = isExcludedGroup(entry.groupTitle)
            val snapshot = xtreamJson.fetchAll(
                host = provider.host, user = provider.username, pass = provider.password,
                userAgent = provider.apiUserAgent(),
                onProgress = { status ->
                    _progress.value = Progress.Parsing(parsed = 0, skipped = 0)
                    progressTracker.setLabel(status)
                },
            )
            val live = snapshot.live.filterNot(::isExcluded)
            val movies = snapshot.movies.filterNot(::isExcluded)
            val seriesMeta = snapshot.seriesMeta.filterNot { isExcludedGroup(it.groupTitle) }
            val totalFetched = snapshot.live.size + snapshot.movies.size + snapshot.seriesMeta.size
            val totalAccepted = live.size + movies.size + seriesMeta.size
            val totalSkipped = totalFetched - totalAccepted
            progressTracker.syncComplete(totalAccepted)
            _progress.value = Progress.PostProcessing
            val sourceKey = provider.toSourceKey()
            persistLive(live, sourceId, sourceKey)
            persistMovies(movies, sourceId, sourceKey)
            persistSeriesCatalogue(seriesMeta)
            runCatching { libraryFilter.recomputeHiddenFlags() }
                .onFailure { e -> android.util.Log.w("SyncService", "recomputeHiddenFlags failed: ${e.message}", e) }
            _progress.value = Progress.Done(totalParsed = totalAccepted, totalSkipped = totalSkipped)
        } catch (e: Throwable) {
            val rootCause = generateSequence(e) { it.cause }.last()
            val msg = buildString {
                append(e::class.simpleName ?: "Throwable")
                e.message?.let { append(": ").append(it) }
                if (rootCause !== e) {
                    append(" (cause: ")
                    append(rootCause::class.simpleName ?: "Throwable")
                    rootCause.message?.let { append(": ").append(it) }
                    append(")")
                }
            }
            _progress.value = Progress.Error(msg)
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Persisters
    // -------------------------------------------------------------------------

    private suspend fun persistLive(
        entries: List<M3uEntry>,
        sourceId: Int,
        sourceKey: String,
    ) {
        // Dedup in-memory first — keeps the per-row writes to one per
        // logical channel.  dedupeWithAlternates also hands back the
        // lower-quality variant URLs so the player can fall back to a
        // playable feed when the best one can't be decoded.
        val deduped = ChannelDeduplicator.dedupeWithAlternates(
            channels = entries,
            displayName = M3uEntry::displayName,
            tvgId = M3uEntry::tvgId,
            streamUrl = M3uEntry::streamUrl,
            withTvgId = { e, id -> e.copy(tvgId = id) },
        )

        // Chunked persist — see persistMovies for rationale.
        for (chunk in deduped.chunked(2000)) {
            val contentRows = ArrayList<ContentItemEntity>(chunk.size)
            val channelRows = ArrayList<ChannelEntity>(chunk.size)

            for (withAlts in chunk) {
                val entry = withAlts.channel
                val contentId = ContentIdHasher.hash(
                    sourceKey = sourceKey,
                    normalisedTitle = TitleParser.normalise(
                        ChannelDeduplicator.cleanChannelName(entry.displayName),
                    ),
                    groupTitle = entry.groupTitle,
                    streamUrl = entry.streamUrl,
                )

                contentRows.add(
                    ContentItemEntity(
                        contentId = contentId,
                        sourceId = sourceId,
                        displayName = entry.displayName,
                        streamUrl = entry.streamUrl,
                        groupTitle = entry.groupTitle,
                        contentType = "live",
                        tvgId = entry.tvgId,
                        tvgName = entry.tvgName,
                        tvgLogo = entry.tvgLogo,
                        tvgType = entry.tvgType,
                        altStreamUrls = withAlts.altStreamUrls.joinToString("\n"),
                    ),
                )

                channelRows.add(
                    ChannelEntity(
                        contentId = contentId,
                        category = ChannelCategorizer.categorise(entry.displayName, entry.groupTitle),
                        logoUrl = entry.tvgLogo,
                        channelNumber = SkyChannelNumbers.numberFor(
                            ChannelDeduplicator.cleanChannelName(entry.displayName),
                        ).takeIf { it != SkyChannelNumbers.UNKNOWN },
                    ),
                )
            }

            // One transaction per chunk so a kill between the two upserts
            // can't leave `content_items` rows with no matching `channels`.
            db.withTransaction {
                contentDao.upsertAll(contentRows)
                channelDao.upsertAll(channelRows)
            }
        }
    }

    private suspend fun persistMovies(
        entries: List<M3uEntry>,
        sourceId: Int,
        sourceKey: String,
    ) {
        // Collapse exact duplicates up front: the same movie is listed
        // once per category by Xtream (e.g. a film in "Action" + "Drama"),
        // all with the same stream URL.  Without this we'd mint a separate
        // content_id per category (group_title is in the hash) and show the
        // film twice.  distinctBy keeps the first listing per stream URL.
        val deduped = entries.distinctBy { it.streamUrl }

        // Chunk the persist so peak memory stays bounded even on very
        // large catalogues (300k+ entries seen on premium IPTV panels).
        for (chunk in deduped.chunked(2000)) {
            val contentRows = ArrayList<ContentItemEntity>(chunk.size)
            val movieRows = ArrayList<MovieEntity>(chunk.size)

            for (entry in chunk) {
                val title = entry.movieTitle ?: TitleParser.stripHdPrefix(entry.displayName)
                val contentId = ContentIdHasher.hash(
                    sourceKey = sourceKey,
                    normalisedTitle = TitleParser.normalise(title),
                    groupTitle = entry.groupTitle,
                    streamUrl = entry.streamUrl,
                )

                contentRows.add(
                    ContentItemEntity(
                        contentId = contentId,
                        sourceId = sourceId,
                        displayName = entry.displayName,
                        streamUrl = entry.streamUrl,
                        groupTitle = entry.groupTitle,
                        contentType = "movie",
                        tvgId = entry.tvgId,
                        tvgName = entry.tvgName,
                        tvgLogo = entry.tvgLogo,
                        tvgType = entry.tvgType,
                        title = title,
                    ),
                )
                movieRows.add(
                    MovieEntity(
                        contentId = contentId,
                        movieTitle = title,
                        year = entry.movieYear,
                        // Provider poster + rating filled instantly so the
                        // library isn't blank pre-TMDB.  TMDB later upgrades
                        // these if it finds a (better) match.
                        posterUrl = entry.posterUrl,
                        rating = entry.rating ?: 0.0,
                    ),
                )
            }

            db.withTransaction {
                // content_items is 100% parse-derived (stream URL, group,
                // tvg-*), so a full upsert is correct — it refreshes those
                // from the latest playlist.
                contentDao.upsertAll(contentRows)
                // movies carries TMDB enrichment + user flags, so we must
                // NOT @Upsert (that resets every column to defaults on each
                // sync).  insertNewOnly leaves existing rows untouched;
                // brand-new movies start with the provider poster/rating.
                movieDao.insertNewOnly(movieRows)
                // Backfill the provider poster/rating onto pre-existing rows
                // that don't yet have a poster (TMDB not run, or older sync
                // before this feature).  Gated on poster_url = '' so a real
                // TMDB poster is never overwritten.
                for (row in movieRows) {
                    if (row.posterUrl.isNotEmpty()) {
                        movieDao.fillProviderArtworkIfMissing(
                            contentId = row.contentId,
                            poster = row.posterUrl,
                            rating = row.rating,
                            year = row.year,
                        )
                    }
                }
            }
        }
    }

    /**
     * Persist a Xtream series catalogue (metadata only — episodes will
     * be lazy-loaded by the show detail screen).  We don't blow away
     * the user's existing per-series progress columns when we re-sync;
     * `upsertAll` preserves them via Room's `@Upsert` matching on the
     * unique `series_title` index, except for `xtream_series_id` which
     * we always overwrite so the lazy fetcher has a current id.
     */
    private suspend fun persistSeriesCatalogue(
        snapshot: List<com.strata.tv.data.xtream.XtreamSeriesMeta>,
    ) {
        if (snapshot.isEmpty()) return
        val rows = snapshot.map { meta ->
            // Preserve totals/last-seen counters across re-sync.  Fill the
            // provider `cover` as the poster instantly (like movies) so the
            // series isn't blank pre-TMDB — but only when we don't already
            // have a (TMDB) poster, so enrichment can still upgrade it.
            val existing = seriesDao.byTitle(meta.title)
            existing?.copy(
                xtreamSeriesId = meta.xtreamSeriesId,
                groupTitle = meta.groupTitle,
                posterUrl = existing.posterUrl.ifBlank { meta.cover },
            )
                ?: SeriesEntity(
                    seriesTitle = meta.title,
                    xtreamSeriesId = meta.xtreamSeriesId,
                    groupTitle = meta.groupTitle,
                    posterUrl = meta.cover,
                )
        }
        seriesDao.upsertAll(rows)
    }

}
