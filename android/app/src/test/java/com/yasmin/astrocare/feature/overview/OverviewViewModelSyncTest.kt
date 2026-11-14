package com.yasmin.astrocare.feature.overview

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.data.contract.ProvenanceRepository
import com.yasmin.astrocare.data.health.HealthRepository
import com.yasmin.astrocare.data.local.AstroCareDatabase
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.local.entity.SymptomCheckInEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.rule.RuleSetRepository
import com.yasmin.astrocare.data.sync.FakeSettingsRepository
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.rule.model.OverallStatusValue
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration coverage for Overview's Room-driven state. Inserts go directly
 * through Room so this verifies reactive observation, not the save pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverviewViewModelSyncTest {

    private lateinit var db: AstroCareDatabase
    private lateinit var viewModel: OverviewViewModel

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
        viewModel = OverviewViewModel(
            healthRepository = health,
            ruleSetRepository = ruleSet,
            mapper = mapper,
            provenanceRepository = ProvenanceRepository(context),
        )
    }

    @After
    fun tearDown() {
        ViewModelStore().apply { put("test", viewModel); clear() }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `new high heart rate updates grid and display warning without creating an alert`() = runBlocking {
        val empty = awaitState { state ->
            !state.loading &&
                state.status?.status == OverallStatusValue.insufficient_data &&
                state.latest.any { it.metric == "heart_rate" && it.valueText == null }
        }
        assertEquals(OverallStatusValue.insufficient_data, empty.status?.status)

        val measuredAt = IsoTime.formatUtc(System.currentTimeMillis())
        val id = UUID.randomUUID().toString()
        db.measurementDao().insertAll(
            listOf(
                MeasurementEntity(
                    id = id,
                    schemaVersion = "1.0",
                    astronautId = "demo-astronaut-01",
                    metric = "heart_rate",
                    valuesJson = "{\"bpm\":120}",
                    measuredAt = measuredAt,
                    utcOffsetMinutes = 0,
                    environment = "iss",
                    source = "manual",
                    isSynthetic = false,
                    note = null,
                    createdAt = measuredAt,
                ),
            ),
        )

        val updated = awaitState { state ->
            state.status?.status == OverallStatusValue.warning &&
                state.latest.any {
                    it.metric == "heart_rate" &&
                        it.valueText == "120 bpm" &&
                        it.observedAt == measuredAt
                }
        }
        assertEquals(OverallStatusValue.warning, updated.status?.status)
        assertEquals(0, db.alertDao().count())
        assertTrue(updated.alerts.isEmpty())
        assertTrue(updated.status?.firingRuleIds?.contains("HR-HIGH-W") == true)
    }

    @Test
    fun `new chest pain check in updates display to emergency without creating an alert`() = runBlocking {
        awaitState { state -> !state.loading && state.latest.isNotEmpty() }

        val checkedAt = IsoTime.formatUtc(System.currentTimeMillis())
        db.symptomCheckInDao().insertAll(
            listOf(
                SymptomCheckInEntity(
                    id = UUID.randomUUID().toString(),
                    schemaVersion = "1.0",
                    astronautId = "demo-astronaut-01",
                    symptomsJson = "[\"chest_pain\"]",
                    checkedAt = checkedAt,
                    utcOffsetMinutes = 0,
                    environment = "iss",
                    source = "manual",
                    isSynthetic = false,
                    chestPainTriageJson = null,
                    createdAt = checkedAt,
                ),
            ),
        )

        val updated = awaitState { state ->
            val status = state.status
            status?.status == OverallStatusValue.critical &&
                status.firingRuleIds.contains("SYM-CHEST-C")
        }
        assertEquals(OverallStatusValue.critical, updated.status?.status)
        assertNotNull(updated.status)
        assertEquals(0, db.alertDao().count())
        assertTrue(updated.alerts.isEmpty())
        assertFalse(updated.errorMessage != null)
    }

    private suspend fun awaitState(predicate: (OverviewState) -> Boolean): OverviewState =
        kotlinx.coroutines.withTimeout(5_000) {
            viewModel.state.first(predicate)
        }
}
