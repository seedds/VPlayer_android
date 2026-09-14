package com.seedds.vplayer.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.seedds.vplayer.server.UploadServerController
import com.seedds.vplayer.server.UploadServerService
import com.seedds.vplayer.ui.nav.AppScaffold
import com.seedds.vplayer.ui.theme.VPlayerTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as VPlayerApp).container }

    /**
     * The upload notification is a courtesy, not a requirement: the service
     * runs either way, so a refusal is simply accepted.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyDefaultOrientation()

        setContent {
            VPlayerTheme {
                AppScaffold(container)
            }
        }

        keepScreenOnWhileUploading()
        requestNotificationPermissionIfNeeded()
        startUploadServer()
    }

    /**
     * Outside the player the app is portrait on phones and landscape on
     * tablets. The player overrides this while it is on screen and restores it
     * on the way out.
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

    fun isTabletLayout(): Boolean =
        resources.configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP

    /**
     * A transfer that stalls because the screen slept is the single most
     * annoying way for this app to fail, so the screen stays on for as long as
     * bytes are moving and is released the moment they stop.
     */
    private fun keepScreenOnWhileUploading() {
        lifecycleScope.launch {
            container.serverController.activeUploadCount.collectLatest { count ->
                if (count > 0) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Starting on the port that is already bound adopts the running server, so
     * this is safe to call on every launch and never interrupts a transfer that
     * is already under way.
     */
    private fun startUploadServer() {
        UploadServerService.start(this, UploadServerController.DEFAULT_PORT)
    }

    private companion object {
        const val TABLET_SMALLEST_WIDTH_DP = 600
    }
}
