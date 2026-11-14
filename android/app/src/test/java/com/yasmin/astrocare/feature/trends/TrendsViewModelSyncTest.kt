package com.yasmin.astrocare.feature.trends

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.data.health.HealthRepository
import com.yasmin.astrocare.data.local.AstroCareDatabase
import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.rule.RuleSetRepository
import com.yasmin.astrocare.data.sync.FakeSettingsRepository
import com.yasmin.astrocare.domain.model.AppSettings
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that Trends rebuilds from Room measurement emissions without a
 * manual refresh, including imported/synced rows that are not upload-pending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrendsViewModelSyncTest {

    private lateinit var db: AstroCareDatabase
    private lateinit var viewModel: TrendsViewModel

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AstroCareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val mapper = ContractMapper(json)
        val ruleSet = RuleSetRepository(context)
        val health = HealthRepository(
            measurementDao = db.measurementDao(),
            symptomCheckInDao = db.symptomCheckInDao(),
            alertDao = db.alertDao(),
            mapper = mapper,
            ruleSetRepository = ruleSet,
            settingsRepository = FakeSettingsRepository(AppSettings()),
            json = json,
        )
        viewModel = TrendsViewModel(
            healthRepository = health,
            ruleSetRepository = ruleSet,
            mapper = mapper,
        )
    }

    @After
    fun tearDown() {
        ViewModelStore().apply { put("test", viewModel); clear() }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `new imported synced measurement changes no data card and adds a trend point without refresh`() = runBlocking {
        val empty = awaitState { state ->
            !state.loading && state.cards.isNotEmpty() &&
                state.cards.first { it.metric == "heart_rate" }.hasNoData
        }
        assertTrue(empty.cards.first { it.metric == "heart_rate" }.hasNoData)

        val measuredAt = IsoTime.formatUtc(System.currentTimeMillis())
        val id = UUID.randomUUID().toString()
        db.measurementDao().insertAll(
            listOf(
                MeasurementEntity(
                    id = id,
                    schemaVersion = "1.0",
                    astronautId = "demo-astronaut-01",
                    metric = "heart_rate",
                    valuesJson = "{\"bpm\":72}",
                    measuredAt = measuredAt,
                    utcOffsetMinutes = 0,
                    environment = "iss",
                    source = "manual",
                    isSynthetic = false,
                    note = null,
                    createdAt = measuredAt,
                    localSyncStatus = SyncStatus.SYNCED,
                ),
            ),
        )

        val updated = awaitState { state ->
            val heartRate = state.cards.firstOrNull { it.metric == "heart_rate" }
            val bpm = heartRate?.trend?.fields?.firstOrNull { it.field == "heart_rate.bpm" }
            heartRate != null && !heartRate.hasNoData && bpm != null &&
                bpm.latest == 72.0 &&
                bpm.points.any { it.count == 1 && it.mean == 72.0 }
        }
        val heartRate = updated.cards.first { it.metric == "heart_rate" }
        val bpm = heartRate.trend.fields.first { it.field == "heart_rate.bpm" }
        assertFalse(heartRate.hasNoData)
        assertEquals(72.0, requireNotNull(bpm.latest), 0.0)
        assertTrue(bpm.points.any { it.count == 1 && it.mean == 72.0 })
        assertEquals(0, db.measurementDao().countPending())
        assertEquals(id, db.measurementDao().findById(id)?.id)
    }

    private suspend fun awaitState(predicate: (TrendsState) -> Boolean): TrendsState =
        kotlinx.coroutines.withTimeout(5_000) {
            viewModel.state.first(predicate)
        }
}
