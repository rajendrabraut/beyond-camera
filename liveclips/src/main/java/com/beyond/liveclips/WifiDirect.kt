package com.beyond.liveclips

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@SuppressLint("MissingPermission")
internal class WifiDirect(
    private val context: Context,
    private val onPeers: (List<WifiP2pDevice>) -> Unit,
    private val onConnected: (WifiP2pInfo) -> Unit,
    private val onFailure: (String) -> Unit
) : Closeable {
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var requested = false
    private var registered = false
    @Volatile private var accepting: ServerSocket? = null
    @Volatile private var connecting: Socket? = null

    val supported = manager != null && context.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = channel ?: return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager?.requestPeers(active) {
                    onPeers(it.deviceList.filter { peer -> peer.status != WifiP2pDevice.UNAVAILABLE }.sortedBy { it.deviceName })
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> manager?.requestConnectionInfo(active) {
                    if (it.groupFormed && requested) {
                        requested = false
                        onConnected(it)
                    }
                }
            }
        }
    }

    fun discover() {
        val active = prepare() ?: return
        requested = true
        manager?.discoverPeers(active, result("Couldn't find Wi-Fi Direct devices."))
    }

    fun connect(device: WifiP2pDevice) {
        val active = prepare() ?: return
        requested = true
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager?.connect(active, config, result("Couldn't connect to this Wi-Fi Direct device."))
    }

    private fun prepare(): WifiP2pManager.Channel? {
        if (!supported || manager == null) return null
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        return channel ?: manager.initialize(context, context.mainLooper) {
            channel = null
            onFailure("Wi-Fi Direct disconnected.")
        }?.also { channel = it }
    }

    suspend fun open(info: WifiP2pInfo): Socket {
        val address = info.groupOwnerAddress ?: throw IOException("Wi-Fi Direct did not provide a group address.")
        if (info.isGroupOwner) {
            return ServerSocket().use { server ->
                accepting = server
                try {
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(address, PORT))
                    server.soTimeout = 30_000
                    server.accept().apply { soTimeout = 30_000 }
                } finally {
                    accepting = null
                }
            }
        }
        repeat(8) {
            currentCoroutineContext().ensureActive()
            val socket = Socket()
            connecting = socket
            try {
                socket.connect(InetSocketAddress(address, PORT), 2_500)
                socket.soTimeout = 30_000
                return socket
            } catch (_: IOException) {
                socket.close()
                delay(400)
            } finally {
                connecting = null
            }
        }
        throw IOException("The other device did not open Bitchat in time.")
    }

    private fun result(message: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) = onFailure("$message ($reason)")
    }

    override fun close() {
        requested = false
        runCatching { accepting?.close() }
        runCatching { connecting?.close() }
        channel?.let { manager?.removeGroup(it, null) }
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    companion object {
        private const val PORT = 39817
    }
}
