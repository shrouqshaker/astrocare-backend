package com.yasmin.astrocare.data.local

/**
 * Android-only sync lifecycle for local rows — part of the offline-first design,
 * never serialized into any contract payload (the contracts/schemas files have no such field).
 *
 * Contract reference for the server-side statuses lives in sync-response.schema.json
 * (`created|duplicate|rejected`); this enum is what the CLIENT tracks locally.
 */
object SyncStatus {
    /** Row waiting for the next `POST /api/v1/sync/batch` attempt. */
    const val PENDING = "pending"

    /** Backend acknowledged the row (`created` or `duplicate`). */
    const val SYNCED = "synced"

    /**
     * Backend permanently rejected the row (validation error). Kept locally for
     * inspection, never auto-retried — per contracts/API.md permanent-error rules.
     */
    const val REJECTED = "rejected"
}
