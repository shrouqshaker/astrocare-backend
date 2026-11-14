package com.yasmin.astrocare.core.navigation

/**
 * App routes. Exactly FOUR main navigation destinations (bottom bar) plus the
 * emergency flow, which is intentionally not a tab: it is reached from a prominent
 * shortcut on Overview and from symptom entry.
 */
object Routes {
    const val OVERVIEW = "overview"
    const val DATA_ENTRY = "data_entry"
    const val TRENDS = "trends"
    const val SETTINGS = "settings"
    const val EMERGENCY = "emergency"

    /** Optional preselected metric on the data-entry screen (e.g. "heart_rate"). */
    const val DATA_ENTRY_ARG = "metric"
    const val DATA_ENTRY_ROUTE = "$DATA_ENTRY?$DATA_ENTRY_ARG={$DATA_ENTRY_ARG}"

    fun dataEntry(metric: String? = null): String =
        if (metric == null) DATA_ENTRY else "$DATA_ENTRY?$DATA_ENTRY_ARG=$metric"
}

/** The four bottom-bar destinations, in display order. */
enum class MainDestination(val route: String, val label: String) {
    OVERVIEW(Routes.OVERVIEW, "Overview"),
    DATA_ENTRY(Routes.DATA_ENTRY, "Data"),
    TRENDS(Routes.TRENDS, "Trends"),
    SETTINGS(Routes.SETTINGS, "Settings"),
}
