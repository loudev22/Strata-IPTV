package com.strata.tv.data.repo

import com.strata.tv.data.db.SourceDao
import com.strata.tv.data.db.SourceEntity
import com.strata.tv.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures the app has a stable source row for the configured Xtream provider.
 * The legacy playlist_url column stores a content-ID namespace, not a
 * downloadable playlist URL.
 *
 * The `sources` row is reused across syncs and its `playlist_url`
 * column is updated when the user changes provider in Settings.
 */
@Singleton
class BootstrapRepository @Inject constructor(
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
) {
    /**
     * Resolve the source row id, creating one if it doesn't exist.
     * Refresh the stable namespace and EPG URL when provider credentials change.
     */
    suspend fun ensureSource(): Int {
        val current = settings.current()
        val sourceKey = if (current.provider.isConfigured) current.provider.toSourceKey() else ""
        val epgUrl = current.provider.toEpgUrl().orEmpty()
        val existing = sourceDao.all().firstOrNull()
        if (existing != null) {
            // Keep the row id stable; refresh URLs if they've changed.
            if (existing.playlistUrl != sourceKey || existing.epgUrl != epgUrl) {
                sourceDao.insert(existing.copy(playlistUrl = sourceKey, epgUrl = epgUrl))
            }
            return existing.id
        }
        return sourceDao.insert(
            SourceEntity(
                playlistUrl = sourceKey,
                epgUrl = epgUrl,
                userAgent = "Strata TV",
            ),
        ).toInt()
    }
}
