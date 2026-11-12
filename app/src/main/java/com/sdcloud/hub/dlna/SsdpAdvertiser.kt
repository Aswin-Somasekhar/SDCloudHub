package com.sdcloud.hub.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SsdpAdvertiser(
    private val context: Context,
    private val uuid: String,
    private val hostIp: String,
    private val httpPort: Int
) {
    companion object {
        private const val TAG = "SsdpAdvertiser"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: MulticastSocket? = null
    private var advertiserJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            // Android Wi-Fi requires acquiring a MulticastLock to receive UDP multicast
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("SDCloudHub::MulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }

            val group = InetAddress.getByName(SSDP_ADDRESS)
            socket = MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                joinGroup(group)
            }

            advertiserJob = scope.launch {
                // 1. Send initial NOTIFY ssdp:alive announcements
                sendAliveNotifications()

                // 2. Launch listener loop for TV M-SEARCH queries
                launch { listenForMSearch() }

                // 3. Periodic heartbeat announcement every 60s
                while (isActive && isRunning) {
                    delay(60_000L)
                    sendAliveNotifications()
                }
            }
            Log.i(TAG, "SSDP Advertiser started on $SSDP_ADDRESS:$SSDP_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SSDP Advertiser", e)
        }
    }

    private fun listenForMSearch() {
        val buffer = ByteArray(2048)
        while (isRunning) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                val message = String(packet.data, 0, packet.length)

                if (message.startsWith("M-SEARCH", ignoreCase = true)) {
                    handleMSearch(message, packet.address, packet.port)
                }
            } catch (e: Exception) {
                if (!isRunning) break
                Log.w(TAG, "Exception reading SSDP socket: ${e.message}")
            }
        }
    }

    private fun handleMSearch(message: String, clientAddress: InetAddress, clientPort: Int) {
        val lines = message.split("\r\n")
        var searchTarget = ""
        for (line in lines) {
            if (line.startsWith("ST:", ignoreCase = true)) {
                searchTarget = line.substring(3).trim()
                break
            }
        }

        val targetsToAnswer = listOf(
            "ssdp:all",
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            "uuid:$uuid"
        )

        if (targetsToAnswer.any { it.equals(searchTarget, ignoreCase = true) }) {
            sendMSearchResponse(clientAddress, clientPort, searchTarget)
        }
    }

    private fun sendMSearchResponse(clientAddress: InetAddress, clientPort: Int, target: String) {
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val dateStr = dateFormat.format(Date())

        val response = "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "DATE: $dateStr\r\n" +
                "EXT:\r\n" +
                "LOCATION: http://$hostIp:$httpPort/description.xml\r\n" +
                "SERVER: Android/14 UPnP/1.0 SDCloudHub/1.0\r\n" +
                "ST: $target\r\n" +
                "USN: uuid:$uuid::$target\r\n\r\n"

        val bytes = response.toByteArray()
        val responsePacket = DatagramPacket(bytes, bytes.size, clientAddress, clientPort)
        try {
            socket?.send(responsePacket)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send M-SEARCH response", e)
        }
    }

    private fun sendAliveNotifications() {
        val targets = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1"
        )
        val group = InetAddress.getByName(SSDP_ADDRESS)

        for (target in targets) {
            val notifyMsg = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$hostIp:$httpPort/description.xml\r\n" +
                    "NT: $target\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Android/14 UPnP/1.0 SDCloudHub/1.0\r\n" +
                    "USN: uuid:$uuid::$target\r\n\r\n"

            val bytes = notifyMsg.toByteArray()
            try {
                val packet = DatagramPacket(bytes, bytes.size, group, SSDP_PORT)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending SSDP NOTIFY: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        advertiserJob?.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            // ignore
        }
        Log.i(TAG, "SSDP Advertiser stopped")
    }
}
