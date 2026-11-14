package com.yasmin.astrocare.data.emergency

import android.content.Context
import com.yasmin.astrocare.domain.emergency.ChestPainProtocol
import com.yasmin.astrocare.domain.emergency.ChestPainProtocolLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled `assets/contracts/chest-pain-protocol.v1.json` once (lazy,
 * off the main thread). The emergency flow is fully offline by contract —
 * no network client is reachable from this class.
 */
@Singleton
class ChestPainProtocolRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val protocol: ChestPainProtocol by lazy {
        ChestPainProtocolLoader.load(
            context.assets.open(PROTOCOL_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() },
        )
    }

    suspend fun protocol(): ChestPainProtocol = withContext(Dispatchers.Default) { protocol }

    companion object {
        const val PROTOCOL_ASSET = "contracts/chest-pain-protocol.v1.json"
    }
}
