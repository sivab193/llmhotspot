package dev.llmhotspot.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class HotspotInfo(val ssid: String, val password: String, val address: String)

class HotspotController(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    suspend fun start(): Result<HotspotInfo> = suspendCancellableCoroutine { continuation ->
        wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                reservation = value
                val credentials = if (Build.VERSION.SDK_INT >= 30) {
                    value.softApConfiguration.ssid.orEmpty() to value.softApConfiguration.passphrase.orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    value.wifiConfiguration?.SSID.orEmpty() to value.wifiConfiguration?.preSharedKey.orEmpty()
                }
                fun resolve(attempt: Int) {
                    if (!continuation.isActive) return
                    val address = localIpv4()
                    when {
                        address != null -> continuation.resume(Result.success(HotspotInfo(credentials.first, credentials.second, address)))
                        attempt < 40 -> Handler(Looper.getMainLooper()).postDelayed({ resolve(attempt + 1) }, 250)
                        else -> {
                            value.close()
                            reservation = null
                            continuation.resume(Result.failure(IllegalStateException("Hotspot started, but its local address could not be resolved")))
                        }
                    }
                }
                resolve(0)
            }
            override fun onFailed(reason: Int) {
                continuation.resume(Result.failure(IllegalStateException("Local-only hotspot failed (reason $reason)")))
            }
            override fun onStopped() { reservation = null }
        }, Handler(Looper.getMainLooper()))
        continuation.invokeOnCancellation { stop() }
    }

    fun stop() { reservation?.close(); reservation = null }

    companion object {
        fun sameWifiIpv4(context: Context): String? {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: return null
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            return connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }

        fun localIpv4(): String? = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
