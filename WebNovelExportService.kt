package com.paperlight.reader.webnovel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.paperlight.reader.MainActivity
import com.paperlight.reader.data.BookDatabase
import com.paperlight.reader.data.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NovelDownloadProgress(val completed: Int, val total: Int, val label: String)

class WebNovelExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL_EXPORT) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val url = intent?.getStringExtra("url")
        val options = intent?.getSerializableExtra("options") as? WebNovelScrapeOptions
        val libraryId = intent?.getStringExtra("libraryId")
        if (url == null || options == null || libraryId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(NotificationManager::class.java)
        val opening = NovelDownloadProgress(0, 0, "Finding chapters…")
        result.value = null
        try {
            checkNotNull(manager).createNotificationChannel(
                NotificationChannel(CHANNEL, "Web EPUB exports", NotificationManager.IMPORTANCE_LOW),
            )
            startForeground(NOTIFICATION_ID, notification(opening))
            val power = checkNotNull(getSystemService(PowerManager::class.java))
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VibeReader:WebEpubExport").apply {
                acquire(6 * 60 * 60 * 1_000L)
            }
        } catch (failure: Exception) {
            result.value = failure.message ?: "Could not start the background export."
            stopSelf()
            return START_NOT_STICKY
        }

        progress.value = opening
        job = scope.launch {
            try {
                val repository = BookRepository(applicationContext, BookDatabase.getInstance(applicationContext).bookDao())
                repository.createEpubFromUrl(url, options, libraryId) { completed, total, label ->
                    val update = NovelDownloadProgress(completed, total, label)
                    progress.value = update
                    manager?.notify(NOTIFICATION_ID, notification(update))
                }.fold(
                    onSuccess = { result.value = "Created ${it.title}.epub" },
                    onFailure = { result.value = it.message ?: "Could not create the EPUB." },
                )
            } catch (cancelled: CancellationException) {
                result.compareAndSet(null, "Download cancelled.")
                throw cancelled
            } catch (failure: Exception) {
                result.value = failure.message ?: "Could not create the EPUB."
            } finally {
                releaseWakeLock()
                progress.value = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        result.value = "Android stopped the export after its background time limit. Try a smaller chapter range."
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        progress.value = null
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notification(update: NovelDownloadProgress): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelExport = PendingIntent.getService(
            this, 0, Intent(this, WebNovelExportService::class.java).setAction(CANCEL_EXPORT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Creating web EPUB")
            .setContentText(if (update.total > 0) "${update.completed}/${update.total} · ${update.label}" else update.label)
            .setProgress(update.total, update.completed, update.total == 0)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Cancel", cancelExport).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "web_epub_export"
        private const val NOTIFICATION_ID = 710
        private const val CANCEL_EXPORT = "com.paperlight.reader.CANCEL_WEB_EXPORT"
        private val progress = MutableStateFlow<NovelDownloadProgress?>(null)
        val downloadProgress = progress.asStateFlow()
        private val result = MutableStateFlow<String?>(null)
        val downloadResult = result.asStateFlow()

        fun consumeResult(message: String): Boolean = result.compareAndSet(message, null)

        fun start(context: Context, url: String, options: WebNovelScrapeOptions, libraryId: String) {
            context.startForegroundService(Intent(context, WebNovelExportService::class.java).apply {
                putExtra("url", url)
                putExtra("options", options)
                putExtra("libraryId", libraryId)
            })
        }

        fun cancel(context: Context) {
            if (progress.value != null) {
                context.startService(Intent(context, WebNovelExportService::class.java).setAction(CANCEL_EXPORT))
            }
        }
    }
}
