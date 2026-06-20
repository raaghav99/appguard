package com.raaghav.appguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

class MonitorService : Service() {

    companion object {
        const val TAG = "AppGuard"
        const val CHANNEL_ID = "appguard_channel"
        const val NOTIF_ID = 1001
        const val CHECK_INTERVAL_MS = 15_000L  // check every 15 seconds
        const val RESTRICT_DELAY_MS = 120_000L // 2 minutes

        // ONLY these apps will ever be touched
        val RESTRICTED_APPS = setOf(
            "com.google.android.googlequicksearchbox",
            "com.truecaller",
            "com.netflix.mediaclient",
            "net.one97.paytm",
            "com.vodafone.vodafoneplay",
            "com.google.android.apps.messaging"
        )

        // These will NEVER be touched no matter what
        private val PROTECTED_APPS = setOf(
            "com.whatsapp",
            "com.instagram.android",
            "com.termux",
            "com.anthropic.claude",
            "moe.shizuku.privileged.api",
            "com.android.launcher",
            "com.android.systemui"
        )

        // Packages starting with these prefixes are never touched
        private val SYSTEM_PREFIXES = listOf(
            "android",
            "com.android.",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.oplus.",
            "com.coloros.",
            "com.realme.",
            "com.qualcomm."
        )

        fun isSafeToRestrict(pkg: String): Boolean {
            if (!RESTRICTED_APPS.contains(pkg)) return false
            if (PROTECTED_APPS.contains(pkg)) return false
            for (prefix in SYSTEM_PREFIXES) {
                if (pkg.startsWith(prefix)) return false
            }
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val closedAt = mutableMapOf<String, Long>()
    private val wasOpen = mutableMapOf<String, Boolean>()

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkApps()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        handler.post(checkRunnable)
        Log.i(TAG, "MonitorService started")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkRunnable)
        Log.i(TAG, "MonitorService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkApps() {
        val foregroundApp = getForegroundApp() ?: return
        val now = System.currentTimeMillis()

        for (pkg in RESTRICTED_APPS) {
            if (!isSafeToRestrict(pkg)) continue

            val isOpen = foregroundApp == pkg

            if (isOpen) {
                // App opened — set active if it wasn't before
                if (wasOpen[pkg] != true) {
                    runShizukuCommand("am", "set-standby-bucket", pkg, "active")
                    Log.i(TAG, "$pkg opened → set active")
                }
                wasOpen[pkg] = true
                closedAt.remove(pkg)
            } else {
                if (wasOpen[pkg] == true) {
                    // Just closed — record time
                    closedAt[pkg] = now
                    Log.i(TAG, "$pkg closed — waiting 2 min")
                }
                wasOpen[pkg] = false

                // Check if 2 minutes have passed since closing
                val closed = closedAt[pkg]
                if (closed != null && (now - closed) >= RESTRICT_DELAY_MS) {
                    runShizukuCommand("am", "force-stop", pkg)
                    runShizukuCommand("am", "set-standby-bucket", pkg, "restricted")
                    Log.i(TAG, "$pkg restricted after 2 min")
                    closedAt.remove(pkg)
                }
            }
        }
    }

    private fun getForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun runShizukuCommand(vararg args: String): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            val process = Shizuku.newProcess(args, null, null)
            process.waitFor()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku command failed: ${e.message}")
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AppGuard",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "App restriction monitor" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AppGuard Active")
            .setContentText("Restricting ${RESTRICTED_APPS.size} apps after 2 min background")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }
}
