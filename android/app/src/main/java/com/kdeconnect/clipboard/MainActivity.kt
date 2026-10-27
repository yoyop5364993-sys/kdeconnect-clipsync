package com.kdeconnect.clipboard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var statusIndicator: View
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var switchSync: MaterialSwitch
    private lateinit var tvPreviewText: TextView
    private lateinit var ivPreview: ImageView

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startClipboardService()
    }

    private val clipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ClipboardSyncService.ACTION_STATUS_CHANGED -> {
                    val connected = intent.getBooleanExtra(ClipboardSyncService.EXTRA_CONNECTED, false)
                    val pcName = intent.getStringExtra(ClipboardSyncService.EXTRA_PC_NAME) ?: ""
                    updateConnectionUI(connected, pcName)
                }
                ClipboardSyncService.ACTION_CLIP_RECEIVED -> {
                    val type = intent.getStringExtra(ClipboardSyncService.EXTRA_TYPE)
                    if (type == "image") {
                        val path = intent.getStringExtra(ClipboardSyncService.EXTRA_IMAGE_PATH)
                        if (path != null) {
                            showImagePreview(path)
                        }
                    } else if (type == "text") {
                        val text = intent.getStringExtra(ClipboardSyncService.EXTRA_TEXT) ?: ""
                        showTextPreview(text)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIndicator = findViewById(R.id.statusIndicator)
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        switchSync = findViewById(R.id.switchSync)
        tvPreviewText = findViewById(R.id.tvPreviewText)
        ivPreview = findViewById(R.id.ivPreview)

        // Make status indicator circular
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#EAB308")) // Yellow searching
        }
        statusIndicator.background = shape

        switchSync.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startClipboardService()
            } else {
                stopClipboardService()
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startClipboardService()
    }

    private fun startClipboardService() {
        val intent = Intent(this, ClipboardSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvStatus.text = "PC 검색 중..."
        setIndicatorColor("#EAB308")
    }

    private fun stopClipboardService() {
        val intent = Intent(this, ClipboardSyncService::class.java)
        stopService(intent)
        tvStatus.text = "동기화 꺼짐"
        setIndicatorColor("#94A3B8")
    }

    private fun updateConnectionUI(connected: Boolean, pcName: String) {
        if (connected) {
            tvStatus.text = "연결됨: $pcName"
            tvDeviceInfo.text = "클립보드가 실시간 동기화 상태입니다."
            setIndicatorColor("#22C55E") // Green
        } else {
            tvStatus.text = "PC 검색 중 (동일 Wi-Fi)..."
            tvDeviceInfo.text = "동일한 Wi-Fi 공유기 또는 핫스팟에 연결되어 있어야 합니다."
            setIndicatorColor("#EAB308") // Yellow
        }
    }

    private fun setIndicatorColor(hexColor: String) {
        (statusIndicator.background as? GradientDrawable)?.setColor(Color.parseColor(hexColor))
    }

    private fun showTextPreview(text: String) {
        ivPreview.visibility = View.GONE
        tvPreviewText.visibility = View.VISIBLE
        tvPreviewText.text = "수신된 텍스트:\n\n$text"
    }

    private fun showImagePreview(imagePath: String) {
        tvPreviewText.visibility = View.GONE
        ivPreview.visibility = View.VISIBLE
        val bitmap = BitmapFactory.decodeFile(imagePath)
        ivPreview.setImageBitmap(bitmap)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ClipboardSyncService.ACTION_STATUS_CHANGED)
            addAction(ClipboardSyncService.ACTION_CLIP_RECEIVED)
        }
        ContextCompat.registerReceiver(
            this,
            clipReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(clipReceiver)
        } catch (e: Exception) {}
    }
}
