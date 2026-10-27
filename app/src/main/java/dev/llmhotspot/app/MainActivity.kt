package dev.llmhotspot.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.llmhotspot.app.service.HotspotService
import dev.llmhotspot.app.ui.LlmHotspotApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var pendingMode by remember { mutableStateOf<NetworkMode?>(null) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                val mode = pendingMode
                pendingMode = null
                if (mode != null && result.values.all { it }) HotspotService.start(this, mode)
                else if (mode != null) AppRuntime.update { it.copy(status = RunStatus.ERROR, message = "Nearby devices permission is required for hotspot mode") }
            }
            LlmHotspotApp(
                application = application as LlmHotspotApplication,
                onStart = { mode ->
                    val requested = buildList {
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (mode == NetworkMode.HOTSPOT) {
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                            else add(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }
                    val missing = requested.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) HotspotService.start(this, mode)
                    else { pendingMode = mode; permissionLauncher.launch(missing.toTypedArray()) }
                },
                onStop = { HotspotService.stop(this) },
                onRotateKey = { HotspotService.rotateKey(this) },
            )
        }
    }
}
