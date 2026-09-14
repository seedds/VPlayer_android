package com.seedds.vplayer.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.seedds.vplayer.ui.nav.AppScaffold
import com.seedds.vplayer.ui.theme.VPlayerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyDefaultOrientation()

        setContent {
            VPlayerTheme {
                AppScaffold((application as VPlayerApp).container)
            }
        }
    }

    /**
     * Outside the player the app is portrait on phones and landscape on
     * tablets, matching the React Native build. The player overrides this for
     * as long as it is on screen and restores it on the way out.
     *
     * Android 16 ignores this on large screens for apps targeting API 36, so on
     * tablets the lock is best-effort and every screen must survive either
     * orientation.
     */
    fun applyDefaultOrientation() {
        requestedOrientation = if (isTabletLayout()) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun isTabletLayout(): Boolean = resources.configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP

    private companion object {
        const val TABLET_SMALLEST_WIDTH_DP = 600
    }
}
