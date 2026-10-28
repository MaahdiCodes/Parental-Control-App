package com.tvguardian.parent.youtube

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.tvguardian.parent.MainActivity
import com.tvguardian.parent.R
import com.tvguardian.parent.auth.AuthRepository
import com.tvguardian.parent.family.FamilyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the YouTube links to TVs like the LG running in the background (with a small ongoing
 * notification, which Android requires). Stops itself when there are no such TVs.
 */
class YouTubeLinkService : Service() {
    companion object {
        private const val CHANNEL_ID = "youtube_link"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            if (FirebaseAuth.getInstance().currentUser == null) return
            ContextCompat.startForegroundService(context, Intent(context, YouTubeLinkService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (job?.isActive != true) {
            job = scope.launch {
                AuthRepository.user.collectLatest { user ->
                    if (user == null) {
                        stopSelf()
                    } else {
                        FamilyRepository.familyId(user.uid).collectLatest { familyId ->
                            YouTubeLinkManager.run(this@YouTubeLinkService, familyId, onNoLinkedTvs = { stopSelf() })
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "YouTube link to TVs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while TV Guardian watches YouTube on TVs linked with a TV code"
            }
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TV Guardian")
            .setContentText("Watching YouTube on your linked TV")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }
}

/** Restarts the YouTube link after the phone reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) YouTubeLinkService.start(context)
    }
}
