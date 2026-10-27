package dev.llmhotspot.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RunStatus { STOPPED, STARTING, LOADING_MODEL, RUNNING, PAUSED_THERMAL, PAUSED_BATTERY, ERROR }
enum class NetworkMode { HOTSPOT, SAME_WIFI }

data class RuntimeState(
    val status: RunStatus = RunStatus.STOPPED,
    val mode: NetworkMode = NetworkMode.HOTSPOT,
    val modelName: String? = null,
    val endpoint: String? = null,
    val apiKey: String? = null,
    val ssid: String? = null,
    val wifiPassword: String? = null,
    val batteryPercent: Int = -1,
    val temperatureC: Float = 0f,
    val tokensServed: Long = 0,
    val clients: Int = 0,
    val message: String? = null,
) {
    val running: Boolean get() = status in setOf(RunStatus.RUNNING, RunStatus.PAUSED_BATTERY, RunStatus.PAUSED_THERMAL)
}

object AppRuntime {
    private val mutable = MutableStateFlow(RuntimeState())
    val state = mutable.asStateFlow()
    fun set(value: RuntimeState) { mutable.value = value }
    fun update(block: (RuntimeState) -> RuntimeState) { mutable.update(block) }
}
