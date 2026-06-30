package com.wickedapp.rokidtg.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.drinkless.tdlib.TdApi
import timber.log.Timber

class NetworkMonitor(ctx: Context, private val td: TdLibClient) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    init {
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.tag("Net").i("available net=%s", network)
                td.send(TdApi.SetNetworkType(TdApi.NetworkTypeWiFi())) {}
            }
            override fun onLost(network: Network) {
                Timber.tag("Net").i("lost net=%s", network)
                td.send(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) {}
            }
        })
    }
}
