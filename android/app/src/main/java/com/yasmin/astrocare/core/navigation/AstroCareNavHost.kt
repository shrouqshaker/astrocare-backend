package com.yasmin.astrocare.core.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yasmin.astrocare.feature.dataentry.DataEntryScreen
import com.yasmin.astrocare.feature.emergency.EmergencyScreen
import com.yasmin.astrocare.feature.overview.OverviewScreen
import com.yasmin.astrocare.feature.settings.SettingsScreen
import com.yasmin.astrocare.feature.trends.TrendsScreen

private fun MainDestination.icon(): ImageVector = when (this) {
    MainDestination.OVERVIEW -> Icons.Filled.Home
    MainDestination.DATA_ENTRY -> Icons.Filled.Edit
    MainDestination.TRENDS -> Icons.Filled.DateRange
    MainDestination.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun AstroCareNavHost(
    modifier: Modifier = Modifier,
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val barHeight = 80.dp * maxOf(1f, LocalDensity.current.fontScale)

    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        navController.navigate(route) { launchSingleTop = true }
        onPendingRouteConsumed()
    }

    val showBottomBar = currentRoute in MainDestination.entries.map { it.route } ||
            currentRoute == Routes.DATA_ENTRY_ROUTE

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        modifier = Modifier.heightIn(min = barHeight),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        MainDestination.entries.forEach { destination ->
                            val selected = backStackEntry?.destination?.hierarchy?.any {
                                it.route == destination.route ||
                                        (destination == MainDestination.DATA_ENTRY && it.route == Routes.DATA_ENTRY_ROUTE)
                            } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(destination.icon(), contentDescription = destination.label) },
                                label = {
                                    Text(
                                        destination.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 2,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.OVERVIEW,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.OVERVIEW) {
                OverviewScreen(onOpenEmergency = { navController.navigate(Routes.EMERGENCY) })
            }
            composable(
                route = Routes.DATA_ENTRY_ROUTE,
                arguments = listOf(navArgument(Routes.DATA_ENTRY_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }),
            ) { entry ->
                DataEntryScreen(
                    metric = entry.arguments?.getString(Routes.DATA_ENTRY_ARG),
                    onOpenEmergency = { navController.navigate(Routes.EMERGENCY) },
                )
            }
            composable(Routes.TRENDS) { TrendsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.EMERGENCY) {
                EmergencyScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}