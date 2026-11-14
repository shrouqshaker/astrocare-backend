package com.yasmin.astrocare

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yasmin.astrocare.core.navigation.AstroCareNavHost
import com.yasmin.astrocare.ui.theme.AstroCareTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Route requested by a reminder notification tap (singleTop: the activity
     * is reused via onNewIntent). Consumed once by [AstroCareNavHost].
     */
    var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)
        enableEdgeToEdge()
        setContent {
            AstroCareTheme {
                AstroCareNavHost(
                    pendingRoute = pendingRoute,
                    onPendingRouteConsumed = { pendingRoute = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(EXTRA_ROUTE)
    }

    companion object {
        const val EXTRA_ROUTE = "com.yasmin.astrocare.ROUTE"
    }
}
