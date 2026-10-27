package dev.llmhotspot.app.ui

import android.graphics.Bitmap
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.llmhotspot.app.AppRuntime
import dev.llmhotspot.app.LlmHotspotApplication
import dev.llmhotspot.app.NetworkMode
import dev.llmhotspot.app.RunStatus
import dev.llmhotspot.app.RuntimeState
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val colors = darkColorScheme(
    primary = Color(0xFF8EA8FF),
    secondary = Color(0xFF73DFC0),
    background = Color(0xFF0B1020),
    surface = Color(0xFF151B31),
)

@Composable
fun LlmHotspotApp(
    application: LlmHotspotApplication,
    onStart: (NetworkMode) -> Unit,
    onStop: () -> Unit,
    onRotateKey: () -> Unit,
) {
    MaterialTheme(colorScheme = colors) {
        val runtime by AppRuntime.state.collectAsStateWithLifecycle()
        var tab by remember { mutableIntStateOf(0) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf("Home" to "◉", "Models" to "▰", "Share" to "⌁", "Settings" to "⚙").forEachIndexed { index, item ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(item.second) }, label = { Text(item.first) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                when (tab) {
                    0 -> HomeScreen(runtime, application.models.selectedPath(), onStart, onStop)
                    1 -> ModelsScreen(application)
                    2 -> ShareScreen(runtime, onRotateKey)
                    else -> SettingsScreen(application)
                }
            }
        }
    }
}

@Composable
private fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HomeScreen(runtime: RuntimeState, modelPath: String?, onStart: (NetworkMode) -> Unit, onStop: () -> Unit) {
    var mode by remember { mutableStateOf(NetworkMode.HOTSPOT) }
    Page("LLMHotspot") {
        Text("Your phone is the API server.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabelValue("Status", runtime.status.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                LabelValue("Model", runtime.modelName ?: modelPath?.let(::File)?.name ?: "No model selected")
                LabelValue("Battery", if (runtime.batteryPercent >= 0) "${runtime.batteryPercent}%" else "—")
                LabelValue("Temperature", if (runtime.temperatureC > 0) "%.1f°C".format(runtime.temperatureC) else "—")
                LabelValue("Tokens served", runtime.tokensServed.toString())
                runtime.message?.let { Text(it, color = if (runtime.status == RunStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (runtime.status in setOf(RunStatus.STARTING, RunStatus.LOADING_MODEL)) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!runtime.running && runtime.status !in setOf(RunStatus.STARTING, RunStatus.LOADING_MODEL)) {
            Text("Network mode", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == NetworkMode.HOTSPOT, onClick = { mode = NetworkMode.HOTSPOT }, label = { Text("Phone hotspot") })
                FilterChip(selected = mode == NetworkMode.SAME_WIFI, onClick = { mode = NetworkMode.SAME_WIFI }, label = { Text("Same Wi-Fi") })
            }
            Button(onClick = { onStart(mode) }, enabled = modelPath != null, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start ${if (mode == NetworkMode.HOTSPOT) "Hotspot" else "Server"}") }
            if (modelPath == null) Text("Import and select a GGUF model on the Models screen first.", color = MaterialTheme.colorScheme.error)
        } else {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Stop") }
        }
    }
}

@Composable
private fun ModelsScreen(application: LlmHotspotApplication) {
    val repository = application.models
    val runtime by AppRuntime.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeDownload by remember { mutableStateOf(repository.pendingDownload()) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            message = "Importing model…"
            repository.import(uri).fold(
                onSuccess = { message = "Imported ${it.name}"; refresh++ },
                onFailure = { message = it.message },
            )
        }
    }
    val installed = remember(refresh) { repository.installed() }
    val selected = remember(refresh) { repository.selectedPath() }
    LaunchedEffect(activeDownload) {
        val active = activeDownload ?: return@LaunchedEffect
        while (true) {
            val state = repository.downloadState(active.first)
            if (state == null) { message = "Download disappeared"; repository.clearDownload(active.second); activeDownload = null; break }
            val progress = if (state.total > 0) (state.downloaded * 100 / state.total) else 0
            message = "Downloading ${active.second.name}: $progress%"
            when (state.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    message = "Verifying SHA-256…"
                    repository.finalizeDownload(active.second).fold(
                        onSuccess = { message = "Downloaded and selected ${it.name}"; refresh++ },
                        onFailure = { message = it.message },
                    )
                    activeDownload = null
                    break
                }
                DownloadManager.STATUS_FAILED -> { message = "Download failed (reason ${state.reason})"; repository.clearDownload(active.second); activeDownload = null; break }
            }
            delay(1_000)
        }
    }
    Page("Models") {
        Button(onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !runtime.running, modifier = Modifier.fillMaxWidth()) { Text("Import GGUF file") }
        message?.let { Text(it) }
        Text("Installed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (installed.isEmpty()) Text("No models installed. Download a tested GGUF in your browser, then import it here.")
        installed.forEach { file ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.name, fontWeight = FontWeight.SemiBold)
                    Text("%.2f GB".format(file.length() / 1_000_000_000.0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { repository.select(file); refresh++; message = "Selected ${file.name}" }, enabled = !runtime.running && selected != file.absolutePath) { Text(if (selected == file.absolutePath) "Selected" else "Select") }
                        OutlinedButton(onClick = { repository.remove(file); refresh++; message = "Deleted ${file.name}" }, enabled = !runtime.running) { Text("Delete") }
                    }
                }
            }
        }
        Text("Tested catalog", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        repository.catalog.forEach { model ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(model.name, fontWeight = FontWeight.SemiBold)
                    Text("${model.ramTierGb} GB+ RAM • ${model.capabilities.joinToString()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (model.url.isNotBlank()) {
                        Button(
                            onClick = { activeDownload = repository.enqueue(model) to model },
                            enabled = !runtime.running && activeDownload == null,
                        ) { Text("Download on Wi-Fi") }
                    } else {
                        Text("Import manually; no publisher-verified download is configured.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareScreen(runtime: RuntimeState, onRotateKey: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val payload = runtime.endpoint?.let { endpoint ->
        "{\"type\":\"llmhotspot\",\"ssid\":${json(runtime.ssid.orEmpty())},\"password\":${json(runtime.wifiPassword.orEmpty())},\"baseUrl\":${json(endpoint)},\"apiKey\":${json(runtime.apiKey.orEmpty())}}"
    }
    Page("Share") {
        if (payload == null) {
            Text("Start the server to create pairing details.")
        } else {
            QrCode(payload)
            LabelValue("Wi-Fi", runtime.ssid ?: "Existing network")
            if (!runtime.wifiPassword.isNullOrBlank()) LabelValue("Wi-Fi password", runtime.wifiPassword)
            LabelValue("Base URL", runtime.endpoint.orEmpty())
            LabelValue("API key", runtime.apiKey.orEmpty())
            Button(onClick = { clipboard.setText(AnnotatedString("OPENAI_BASE_URL=${runtime.endpoint}\nOPENAI_API_KEY=${runtime.apiKey}")) }, modifier = Modifier.fillMaxWidth()) { Text("Copy API settings") }
            OutlinedButton(onClick = onRotateKey, modifier = Modifier.fillMaxWidth()) { Text("Regenerate and revoke key") }
            Text("The QR contains Wi-Fi credentials plus endpoint and key as an LLMHotspot pairing bundle. Generic camera apps may display it as text; copy settings is the universal fallback.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsScreen(application: LlmHotspotApplication) {
    val localContext = LocalContext.current
    val powerManager = remember { localContext.getSystemService(PowerManager::class.java) }
    var batteryExempt by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(localContext.packageName)) }
    val prefs = remember { application.getSharedPreferences("llmhotspot", android.content.Context.MODE_PRIVATE) }
    var port by remember { mutableStateOf(prefs.getInt("port", 8080).toString()) }
    var battery by remember { mutableStateOf(prefs.getInt("battery_stop", 20).toString()) }
    var thermal by remember { mutableStateOf(prefs.getFloat("thermal_pause", 45f).toString()) }
    var context by remember { mutableStateOf(prefs.getInt("context_size", 8192).toString()) }
    var saved by remember { mutableStateOf(false) }
    Page("Settings") {
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Server port") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(context, { context = it.filter(Char::isDigit) }, label = { Text("Context tokens") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(battery, { battery = it.filter(Char::isDigit) }, label = { Text("Auto-stop battery %") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(thermal, { thermal = it.filter { char -> char.isDigit() || char == '.' } }, label = { Text("Thermal pause °C") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            prefs.edit()
                .putInt("port", port.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080)
                .putInt("context_size", context.toIntOrNull()?.coerceIn(512, 32768) ?: 8192)
                .putInt("battery_stop", battery.toIntOrNull()?.coerceIn(5, 80) ?: 20)
                .putFloat("thermal_pause", thermal.toFloatOrNull()?.coerceIn(38f, 55f) ?: 45f)
                .apply()
            saved = true
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (saved) Text("Saved. New values apply next time the server starts.", color = MaterialTheme.colorScheme.secondary)
        if (!batteryExempt) {
            OutlinedButton(onClick = {
                runCatching {
                    localContext.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${localContext.packageName}")))
                    batteryExempt = powerManager.isIgnoringBatteryOptimizations(localContext.packageName)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Allow uninterrupted sessions") }
            Text("Android may stop long inference sessions unless LLMHotspot is excluded from battery optimization.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Recommended defaults: 8K context, Q8_0 KV cache, stop at 20%, pause at 45°C.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QrCode(value: String) {
    val bitmap = remember(value) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 720, 720)
        Bitmap.createBitmap(720, 720, Bitmap.Config.RGB_565).apply {
            for (y in 0 until 720) for (x in 0 until 720) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(bitmap.asImageBitmap(), contentDescription = "Pairing QR code", modifier = Modifier.size(280.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp))
    }
}

private fun json(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); else -> append(character) }
    }
    append('"')
}
