package com.wickedapp.rokidtg.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.ui.BannerHost
import org.drinkless.tdlib.TdApi
import timber.log.Timber

class NetworkMonitor(ctx: Context, private val td: TdLibClient) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Timber.tag("Net").i("available net=%s", network)
            // Caps not yet delivered on onAvailable; default to WiFi until onCapabilitiesChanged.
            td.send(TdApi.SetNetworkType(TdApi.NetworkTypeWiFi())) {}
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                    if (roaming) TdApi.NetworkTypeMobileRoaming() else TdApi.NetworkTypeMobile()
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> TdApi.NetworkTypeWiFi()
                else -> TdApi.NetworkTypeOther()
            }
            td.send(TdApi.SetNetworkType(type)) {}
        }

        override fun onLost(network: Network) {
            Timber.tag("Net").i("lost net=%s", network)
            td.send(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) {}
            // BannerHost.show posts to main looper internally; safe from callback thread.
            BannerHost.show(ctx.getString(R.string.offline))
        }
    }

    init {
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, callback)
    }

    /** Unregister the network callback. Call from TelegramService.onDestroy. */
    fun close() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
