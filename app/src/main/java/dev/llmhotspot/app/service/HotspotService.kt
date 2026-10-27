package dev.llmhotspot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.llmhotspot.app.AppRuntime
import dev.llmhotspot.app.LlmHotspotApplication
import dev.llmhotspot.app.MainActivity
import dev.llmhotspot.app.NetworkMode
import dev.llmhotspot.app.RunStatus
import dev.llmhotspot.app.RuntimeState
import dev.llmhotspot.app.network.HotspotController
import dev.llmhotspot.nativebridge.LlamaEngine
import dev.llmhotspot.server.LlmHotspotServer
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class HotspotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var hotspot: HotspotController
    private lateinit var engine: LlamaEngine
    private var server: LlmHotspotServer? = null
    private var work: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        hotspot = HotspotController(this)
        engine = LlamaEngine()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_ROTATE_KEY -> { rotateKey(); return START_NOT_STICKY }
        }
        if (work?.isActive == true) return START_NOT_STICKY
        startForegroundNow("Starting…")
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { NetworkMode.valueOf(it) }.getOrNull() } ?: NetworkMode.HOTSPOT
        work = scope.launch { startRuntime(mode) }
        return START_NOT_STICKY
    }

    private suspend fun startRuntime(mode: NetworkMode) {
        val app = application as LlmHotspotApplication
        val path = app.models.selectedPath()
        if (path == null) return fail("Choose or import a GGUF model first")
        val prefs = getSharedPreferences("llmhotspot", MODE_PRIVATE)
        val port = prefs.getInt("port", 8080).coerceIn(1024, 65535)
        val key = newKey()
        AppRuntime.set(RuntimeState(status = RunStatus.STARTING, mode = mode, modelName = java.io.File(path).name, apiKey = key))
        acquireLocks()

        val network = if (mode == NetworkMode.HOTSPOT) {
            hotspot.start().getOrElse { return fail(it.message ?: "Unable to start hotspot") }
        } else {
            val address = HotspotController.sameWifiIpv4(this) ?: return fail("Connect the phone to Wi-Fi before starting same-Wi-Fi mode")
            dev.llmhotspot.app.network.HotspotInfo("Existing Wi-Fi", "", address)
        }

        AppRuntime.update { it.copy(status = RunStatus.LOADING_MODEL, ssid = network.ssid, wifiPassword = network.password, endpoint = "http://${network.address}:$port/v1") }
        val contextSize = prefs.getInt("context_size", 8192)
        engine.load(path, contextSize).getOrElse { return fail(it.message ?: "Model load failed") }
        val apiServer = LlmHotspotServer(
            inferenceEngine = engine,
            initialApiKey = key,
            onTokens = { count -> AppRuntime.update { it.copy(tokensServed = it.tokensServed + count) } },
            isAcceptingRequests = { AppRuntime.state.value.status == RunStatus.RUNNING },
        )
        runCatching { apiServer.start(network.address, port) }.getOrElse { return fail("HTTP server failed: ${it.message}") }
        server = apiServer
        AppRuntime.update { it.copy(status = RunStatus.RUNNING, message = "Ready for local clients") }
        updateNotification("Running • ${java.io.File(path).name}")
        monitorGuardrails()
    }

    private suspend fun monitorGuardrails() {
        val prefs = getSharedPreferences("llmhotspot", MODE_PRIVATE)
        val stopAt = prefs.getInt("battery_stop", 20).coerceIn(5, 80)
        val warnAt = (stopAt + 10).coerceAtMost(90)
        val pauseAt = prefs.getFloat("thermal_pause", 45f).coerceIn(38f, 55f)
        val resumeAt = (pauseAt - 3f).coerceAtLeast(35f)
        val battery = getSystemService(BatteryManager::class.java)
        while (scope.isActive && AppRuntime.state.value.status != RunStatus.ERROR) {
            val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val temperature = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f) ?: 0f
            val current = AppRuntime.state.value
            if (percent in 0..stopAt) {
                AppRuntime.update { it.copy(batteryPercent = percent, temperatureC = temperature, message = "Stopped at the $stopAt% battery limit") }
                stopSelf()
                return
            }
            val nextStatus = when {
                temperature >= pauseAt -> RunStatus.PAUSED_THERMAL
                current.status == RunStatus.PAUSED_THERMAL && temperature > resumeAt -> RunStatus.PAUSED_THERMAL
                else -> RunStatus.RUNNING
            }
            val message = when {
                nextStatus == RunStatus.PAUSED_THERMAL -> "Paused at %.1f°C; resumes below %.1f°C".format(temperature, resumeAt)
                percent in 0..warnAt -> "Battery low: $percent%"
                else -> "Ready for local clients"
            }
            AppRuntime.update { it.copy(status = nextStatus, batteryPercent = percent, temperatureC = temperature, message = message) }
            updateNotification(if (nextStatus == RunStatus.PAUSED_THERMAL) "Paused • device warm" else "Running • $percent% battery")
            delay(15_000)
        }
    }

    private fun rotateKey() {
        if (server == null) return
        val key = newKey()
        server?.rotateKey(key)
        AppRuntime.update { it.copy(apiKey = key, message = "API key regenerated; old key revoked") }
    }

    private suspend fun fail(message: String) {
        server?.stop(); server = null
        engine.unload()
        hotspot.stop()
        releaseLocks()
        AppRuntime.update { it.copy(status = RunStatus.ERROR, message = message) }
        updateNotification("Error • open app for details")
        delay(2_000)
        stopSelf()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LLMHotspot:Inference").apply { acquire() }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "LLMHotspot:Network").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
    }

    private fun startForegroundNow(text: String) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, HotspotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("LLM Hotspot")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LLM server", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        work?.cancel()
        server?.stop(); server = null
        runBlocking(Dispatchers.IO) { engine.unload() }
        hotspot.stop()
        releaseLocks()
        scope.cancel()
        if (AppRuntime.state.value.status != RunStatus.ERROR) AppRuntime.set(RuntimeState(message = AppRuntime.state.value.message))
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppRuntime.update { it.copy(message = "Android foreground-service time limit reached") }
        stopSelf(startId)
    }

    companion object {
        const val ACTION_START = "dev.llmhotspot.START"
        const val ACTION_STOP = "dev.llmhotspot.STOP"
        const val ACTION_ROTATE_KEY = "dev.llmhotspot.ROTATE_KEY"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_ID = "llmhotspot-server"
        private const val NOTIFICATION_ID = 701

        fun start(context: Context, mode: NetworkMode) {
            val intent = Intent(context, HotspotService::class.java).setAction(ACTION_START).putExtra(EXTRA_MODE, mode.name)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) { context.startService(Intent(context, HotspotService::class.java).setAction(ACTION_STOP)) }
        fun rotateKey(context: Context) { context.startService(Intent(context, HotspotService::class.java).setAction(ACTION_ROTATE_KEY)) }

        private fun newKey(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
    }
}
