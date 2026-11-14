package com.yasmin.astrocare

import java.io.File

/**
 * Locates frozen contract files from unit tests regardless of Gradle's working
 * directory (module dir `app/` or repo root).
 */
internal fun resolveFile(vararg candidates: String): File =
    candidates.firstNotNullOfOrNull { p -> File(p).takeIf { it.isFile } }
        ?: error("File not found. Tried: ${candidates.joinToString()}")

/** e.g. contractExample("measurement.json") → contracts/examples/measurement.json */
internal fun contractExample(name: String): File =
    resolveFile("../contracts/examples/$name", "contracts/examples/$name")

/** Bundled asset path (as AssetManager sees it), e.g. `contracts/synthetic-seed.v1.json`. */
internal fun bundledAsset(assetPath: String): File =
    resolveFile("src/main/assets/$assetPath", "app/src/main/assets/$assetPath")
