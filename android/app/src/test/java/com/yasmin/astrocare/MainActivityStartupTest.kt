package com.yasmin.astrocare

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Regression: construct the real Hilt activity, Compose navigation and start destination. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityStartupTest {
    @Test
    fun `launcher activity cold starts and can recreate`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.setup()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertNotNull(controller.get().window.decorView)
            assertFalse(controller.get().isFinishing)
            controller.recreate()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertFalse(controller.get().isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
