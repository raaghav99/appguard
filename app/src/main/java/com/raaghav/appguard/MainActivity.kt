package com.raaghav.appguard

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)

        startBtn.setOnClickListener {
            when {
                !hasUsageStatsPermission() -> {
                    statusText.text = "Grant Usage Access permission first"
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                !Shizuku.pingBinder() -> {
                    statusText.text = "Shizuku is not running. Start it first."
                    Toast.makeText(this, "Open Shizuku app and start the service", Toast.LENGTH_LONG).show()
                }
                Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                    statusText.text = "Requesting Shizuku permission..."
                    Shizuku.requestPermission(1001)
                }
                else -> {
                    startMonitorService()
                    statusText.text = "Running — monitoring ${MonitorService.RESTRICTED_APPS.size} apps"
                }
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, MonitorService::class.java))
            statusText.text = "Stopped"
        }

        // Auto-start if everything is ready
        if (hasUsageStatsPermission() && Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMonitorService()
            statusText.text = "Running — monitoring ${MonitorService.RESTRICTED_APPS.size} apps"
        } else {
            statusText.text = "Tap Start and follow the prompts"
        }

        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startMonitorService()
                statusText.text = "Running — monitoring ${MonitorService.RESTRICTED_APPS.size} apps"
            }
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
