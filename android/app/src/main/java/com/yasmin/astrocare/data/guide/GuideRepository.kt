package com.yasmin.astrocare.data.guide

import android.content.Context
import com.yasmin.astrocare.domain.guide.Guide
import com.yasmin.astrocare.domain.guide.GuideSet
import com.yasmin.astrocare.domain.guide.GuideSetLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled `assets/contracts/guides.v1.json` once (lazy, off the main
 * thread). Offline by construction — no network client is reachable here;
 * device illustrations are bundled PNGs referenced by `assetName`.
 */
@Singleton
class GuideRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val guideSet: GuideSet by lazy {
        GuideSetLoader.load(
            context.assets.open(GUIDES_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }

    suspend fun guideSet(): GuideSet = withContext(Dispatchers.Default) { guideSet }

    suspend fun guideForMetric(metric: String): Guide? = withContext(Dispatchers.Default) {
        guideSet.forMetric(metric)
    }

    companion object {
        const val GUIDES_ASSET = "contracts/guides.v1.json"
    }
}
