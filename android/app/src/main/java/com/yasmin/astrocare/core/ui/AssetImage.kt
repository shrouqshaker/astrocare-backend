package com.yasmin.astrocare.core.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * Loads a bundled illustration from `assets/<assetName>.png` exactly once.
 * The five device illustrations are shipped in the APK — never downloaded.
 * Returns null (callers show a text fallback) if the asset is missing.
 */
@Composable
fun rememberAssetImage(assetName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(assetName) {
        runCatching {
            context.assets.open("$assetName.png").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }
}
