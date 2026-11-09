package com.strata.tv.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.settings.ProviderConfig
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.testing.FakeXtreamServer
import com.strata.tv.testing.resetForTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** End-to-end test of Xtream catalogue exclusion filters. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExclusionFilterTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var syncService: SyncService
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var channelDao: ChannelDao
    private lateinit var fakeServer: FakeXtreamServer
    private lateinit var provider: ProviderConfig

    @Before fun setup() {
        hilt.inject()
        settings.resetForTest()
        fakeServer = FakeXtreamServer().apply {
            liveCategoriesJson = """
                [
                  {"category_id":"uk","category_name":"UK | Entertainment"},
                  {"category_id":"adult","category_name":"XXX"},
                  {"category_id":"us","category_name":"US | News"},
                  {"category_id":"adult2","category_name":"For Adults"}
                ]
            """.trimIndent()
            liveStreamsJson = """
                [
                  {"num":1,"name":"BBC One HD","stream_type":"live","stream_id":101,"stream_icon":"","epg_channel_id":"bbc1","category_id":"uk"},
                  {"num":2,"name":"Adult Channel 1","stream_type":"live","stream_id":102,"stream_icon":"","epg_channel_id":"xxx1","category_id":"adult"},
                  {"num":3,"name":"CNN HD","stream_type":"live","stream_id":103,"stream_icon":"","epg_channel_id":"cnn","category_id":"us"},
                  {"num":4,"name":"Adult Channel 2","stream_type":"live","stream_id":104,"stream_icon":"","epg_channel_id":"xxx2","category_id":"adult2"}
                ]
            """.trimIndent()
            start()
        }
        provider = ProviderConfig(providerId="custom_xtream",host=fakeServer.host(),username="test",password="secret")
    }
    @After fun teardown() { fakeServer.shutdown() }

    @Test fun sync_filtersOutExcludedGroups() = runTest {
        settings.setProvider(provider)
        syncService.syncFromXtream(provider, sourceId=1)
        assertThat(channelDao.watchAll().first()).hasSize(2)
    }

    @Test fun sync_withEmptyExclusionList_keepsEverything() = runTest {
        settings.setProvider(provider)
        settings.setExcludedCategories(emptySet())
        settings.setCountryWhitelist(emptySet())
        syncService.syncFromXtream(provider, sourceId=1)
        assertThat(channelDao.watchAll().first()).hasSize(4)
    }
}
