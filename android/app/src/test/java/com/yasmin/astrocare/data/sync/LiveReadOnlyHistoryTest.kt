package com.yasmin.astrocare.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yasmin.astrocare.core.common.AppDefaults
import com.yasmin.astrocare.data.local.AstroCareDatabase
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.remote.HistoryClient
import com.yasmin.astrocare.data.remote.HistoryTransport
import com.yasmin.astrocare.data.remote.dto.HistorySnapshot
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.SyncSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in live GET smoke test. It has no SyncTransport, POST, seed or delete operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveReadOnlyHistoryTest {
    @Test
    fun `actual Retrofit history response validates and merges without writes to the server`() = runBlocking {
        assumeTrue(System.getenv("ASTROCARE_LIVE_READ_ONLY") == "true")
        val baseUrl = System.getenv("ASTROCARE_LIVE_BASE_URL") ?: AppDefaults.DEFAULT_BASE_URL
        val apiKey = System.getenv("ASTROCARE_LIVE_API_KEY") ?: AppDefaults.DEFAULT_API_KEY
        val astronautId = AppDefaults.DEFAULT_ASTRONAUT_ID
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val client = HistoryClient(json, OkHttpClient.Builder().build())
        val snapshot = client.fetch(baseUrl, apiKey, astronautId)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AstroCareDatabase::class.java).build()
        try {
            // Capture one response: repeated local merge is deterministic even if the server receives new data later.
            val transport = object : HistoryTransport {
                override suspend fun fetch(baseUrl: String, apiKey: String, astronautId: String): HistorySnapshot = snapshot
            }
            val settings = FakeSettingsRepository(AppSettings(deviceId = "read-only-test", sync = SyncSettings(baseUrl = baseUrl, apiKey = apiKey)))
            val repository = HistoryRepository(db, ContractMapper(json), transport, settings)
            val first = repository.pull(baseUrl, apiKey, astronautId)
            val second = repository.pull(baseUrl, apiKey, astronautId)
            assertEquals(first.downloaded, first.inserted)
            assertEquals(0, second.inserted)
            assertTrue(first.conflicts.isEmpty())
            assertEquals(0, db.measurementDao().countPending())
            assertEquals(0, db.symptomCheckInDao().countPending())
            assertEquals(0, db.alertDao().countPending())
            println("READ_ONLY_HISTORY measurements=${snapshot.measurements.size} checkins=${snapshot.symptomCheckins.size} alerts=${snapshot.alerts.size} inserted=${first.inserted} conflicts=${first.conflicts.size}")
        } finally {
            db.close()
        }
    }
}
