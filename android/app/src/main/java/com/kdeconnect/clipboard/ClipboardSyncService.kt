package com.kdeconnect.clipboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

class ClipboardSyncService : Service() {

    companion object {
        const val TAG = "ClipSyncService"
        const val PORT = 1716
        const val CHANNEL_ID = "clipsync_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_CLIP_RECEIVED = "com.kdeconnect.clipboard.ACTION_CLIP_RECEIVED"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TEXT = "text"
        const val EXTRA_IMAGE_PATH = "image_path"
        const val ACTION_STATUS_CHANGED = "com.kdeconnect.clipboard.ACTION_STATUS_CHANGED"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_PC_NAME = "pc_name"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var activeSocket: Socket? = null
    private var lastClipboardHash: String = ""
    private val deviceId = "android_" + UUID.randomUUID().toString().substring(0, 8)
    private val deviceName = Build.MODEL ?: "Android Device"

    private lateinit var clipboardManager: ClipboardManager

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("PC 검색 중..."))

        acquireMulticastLock()
        startTcpServer()
        startUdpDiscovery()
        listenLocalClipboard()
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("ClipSyncMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clipboard Sync Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KDE Connect ClipSync")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // 1. TCP Server for PC connections
    private fun startTcpServer() {
        serviceScope.launch {
            try {
                val serverSocket = ServerSocket(PORT)
                while (isActive) {
                    val client = serverSocket.accept()
                    handleTcpConnection(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP Server error: ${e.message}")
            }
        }
    }

    // 2. UDP Discovery listener & advertiser
    private fun startUdpDiscovery() {
        serviceScope.launch {
            try {
                val udpSocket = DatagramSocket(PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }

                // Discovery broadcast loop
                launch {
                    val identityPacket = JSONObject().apply {
                        put("id", System.currentTimeMillis())
                        put("type", "kdeconnect.identity")
                        put("body", JSONObject().apply {
                            put("deviceId", deviceId)
                            put("deviceName", deviceName)
                            put("protocolVersion", 7)
                            put("deviceType", "phone")
                            put("tcpPort", PORT)
                        })
                    }.toString() + "\n"

                    val data = identityPacket.toByteArray(Charsets.UTF_8)
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")

                    while (isActive) {
                        try {
                            val packet = DatagramPacket(data, data.size, broadcastAddr, PORT)
                            udpSocket.send(packet)
                        } catch (e: Exception) {
                            // ignore network changes
                        }
                        delay(4000)
                    }
                }

                // Incoming UDP Packet listener
                val buffer = ByteArray(4096)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket.receive(packet)
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleUdpPacket(raw, packet.address)
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP Error: ${e.message}")
            }
        }
    }

    private fun handleUdpPacket(raw: String, address: InetAddress) {
        try {
            val json = JSONObject(raw)
            if (json.optString("type") == "kdeconnect.identity") {
                val body = json.optJSONObject("body") ?: return
                val remoteId = body.optString("deviceId")
                val remoteName = body.optString("deviceName", "Windows 11 PC")
                val remotePort = body.optInt("tcpPort", PORT)

                if (remoteId != deviceId && activeSocket == null) {
                    Log.d(TAG, "Discovered PC: $remoteName at ${address.hostAddress}:$remotePort")
                    connectToPc(address, remotePort, remoteName)
                }
            }
        } catch (e: Exception) {
            // parse error
        }
    }

    private fun connectToPc(address: InetAddress, port: Int, pcName: String) {
        serviceScope.launch {
            try {
                val socket = Socket(address, port)
                handleTcpConnection(socket, pcName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to PC: ${e.message}")
            }
        }
    }

    private fun handleTcpConnection(socket: Socket, targetPcName: String? = null) {
        activeSocket = socket
        var pcName = targetPcName ?: "Windows 11"
        updateNotification("연결됨: $pcName")
        notifyStatus(true, pcName)

        serviceScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    parseIncomingPacket(line)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Socket disconnected: ${e.message}")
            } finally {
                activeSocket = null
                updateNotification("PC 검색 중...")
                notifyStatus(false, "")
            }
        }
    }

    private suspend fun parseIncomingPacket(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type")
            val body = json.optJSONObject("body") ?: return

            when (type) {
                "kdeconnect.clipboard" -> {
                    val text = body.optString("content")
                    if (text.isNotEmpty() && hash(text) != lastClipboardHash) {
                        lastClipboardHash = hash(text)
                        withContext(Dispatchers.Main) {
                            val clip = ClipData.newPlainText("text", text)
                            clipboardManager.setPrimaryClip(clip)
                            notifyClipReceived("text", text, null)
                        }
                    }
                }
                "kdeconnect.clipboard.image" -> {
                    val base64Data = body.optString("data")
                    if (base64Data.isNotEmpty() && hash(base64Data) != lastClipboardHash) {
                        lastClipboardHash = hash(base64Data)
                        processReceivedImage(base64Data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Packet parse error: ${e.message}")
        }
    }

    // Process Received Image from Windows 11
    private suspend fun processReceivedImage(base64Data: String) {
        withContext(Dispatchers.IO) {
            try {
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val imagesDir = File(cacheDir, "clipboard").apply { mkdirs() }
                val imageFile = File(imagesDir, "clip_${System.currentTimeMillis()}.png")

                FileOutputStream(imageFile).use { fos ->
                    fos.write(imageBytes)
                }

                val imageUri: Uri = FileProvider.getUriForFile(
                    this@ClipboardSyncService,
                    "${applicationContext.packageName}.fileprovider",
                    imageFile
                )

                withContext(Dispatchers.Main) {
                    val clipData = ClipData.newUri(contentResolver, "Image", imageUri).apply {
                        description.extras = android.os.Bundle().apply {
                            putStringArray(ClipDescription.EXTRA_MIMETYPES, arrayOf("image/png"))
                        }
                    }
                    clipboardManager.setPrimaryClip(clipData)
                    notifyClipReceived("image", null, imageFile.absolutePath)
                    updateNotification("새 이미지 클립보드 수신됨 (${imageBytes.size / 1024} KB)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save and set image to clipboard: ${e.message}")
            }
        }
    }

    private fun listenLocalClipboard() {
        clipboardManager.addPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip ?: return@addPrimaryClipChangedListener
            if (clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.text?.toString()
                if (!text.isNullOrEmpty() && hash(text) != lastClipboardHash) {
                    lastClipboardHash = hash(text)
                    sendTextToPc(text)
                }
            }
        }
    }

    private fun sendTextToPc(text: String) {
        val socket = activeSocket ?: return
        serviceScope.launch {
            try {
                val packet = JSONObject().apply {
                    put("id", System.currentTimeMillis())
                    put("type", "kdeconnect.clipboard")
                    put("body", JSONObject().apply {
                        put("content", text)
                    })
                }.toString() + "\n"

                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                writer.write(packet)
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Send text error: ${e.message}")
            }
        }
    }

    private fun notifyClipReceived(type: String, text: String?, imagePath: String?) {
        val intent = Intent(ACTION_CLIP_RECEIVED).apply {
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_IMAGE_PATH, imagePath)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun notifyStatus(connected: Boolean, pcName: String) {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_CONNECTED, connected)
            putExtra(EXTRA_PC_NAME, pcName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        multicastLock?.release()
        try { activeSocket?.close() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
