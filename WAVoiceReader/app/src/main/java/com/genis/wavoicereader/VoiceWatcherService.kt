package com.genis.wavoicereader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.Executors

class VoiceWatcherService : Service() {

    companion object {
        private const val TAG = "VoiceWatcherService"
        private const val CHANNEL_ID = "wa_voice_watcher"
        private const val NOTIF_ID = 1
        private const val POLL_INTERVAL_MS = 8_000L
        private const val STABLE_CHECK_DELAY_MS = 1200L

        // Возможные пути к папке голосовых сообщений WhatsApp на разных версиях Android/WA
        val CANDIDATE_DIRS = listOf(
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes",
            "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Voice Notes"
        )

        // Отдельная папка ТОЛЬКО для тестовой кнопки — никак не связана с WhatsApp.
        // Нужна, чтобы можно было проверить всю цепочку (файл → распознавание → оверлей)
        // даже если WhatsApp на телефоне не установлен вообще.
        val TEST_DIR = "/storage/emulated/0/WAVoiceReaderTest/VoiceNotes"
    }

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileObservers = mutableListOf<FileObserver>()
    private var pollRunnable: Runnable? = null
    private lateinit var overlay: OverlayController

    private val watchedDirs = mutableListOf<File>()
    private val knownFiles = mutableSetOf<String>()
    private var serviceStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayController(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Ожидание новых голосовых сообщений…"))
        serviceStartTime = System.currentTimeMillis()
        setupWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fileObservers.forEach { it.stopWatching() }
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        executor.shutdownNow()
    }

    private fun setupWatcher() {
        // 1. Реальные папки WhatsApp — берём все, что реально существуют на телефоне.
        val realDirs = CANDIDATE_DIRS.map { File(it) }.filter { it.exists() && it.isDirectory }

        // 2. Тестовая папка — создаём её всегда, даже если WhatsApp не установлен,
        //    чтобы тестовая кнопка могла в неё писать, а сервис — её слушать.
        val testDir = File(TEST_DIR)
        if (!testDir.exists()) testDir.mkdirs()

        val allDirs = (realDirs + testDir).distinctBy { it.absolutePath }
        watchedDirs.clear()
        watchedDirs.addAll(allDirs)

        if (realDirs.isEmpty()) {
            Log.w(TAG, "Папка голосовых WhatsApp не найдена — слежу только за тестовой папкой")
        }

        for (dir in allDirs) {
            knownFiles.addAll(dir.listFiles()?.map { it.absolutePath } ?: emptyList())
            startFileObserver(dir)
        }
        startPolling()

        val names = allDirs.joinToString(", ") { it.name }
        updateNotification("Слежу за: $names")
    }

    @Suppress("DEPRECATION")
    private fun startFileObserver(dir: File) {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val obs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) handleCandidate(File(dir, path))
                }
            }
        } else {
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) handleCandidate(File(dir, path))
                }
            }
        }
        obs.startWatching()
        fileObservers.add(obs)
    }

    /** Резервный опрос всех наблюдаемых папок на случай, если FileObserver пропустит событие. */
    private fun startPolling() {
        pollRunnable = object : Runnable {
            override fun run() {
                watchedDirs.forEach { dir -> dir.listFiles()?.forEach { f -> handleCandidate(f) } }
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(pollRunnable!!, POLL_INTERVAL_MS)
    }

    private fun handleCandidate(file: File) {
        if (!file.exists() || !file.isFile) return
        if (file.absolutePath in knownFiles) return
        if (file.lastModified() < serviceStartTime - 5_000) return // игнорируем старые файлы
        if (!file.name.lowercase().endsWith(".opus") && !file.name.lowercase().endsWith(".ogg")) return

        knownFiles.add(file.absolutePath)
        executor.execute { processFile(file) }
    }

    private fun processFile(file: File) {
        // ждём, пока WhatsApp закончит запись файла (размер должен стабилизироваться)
        val sizeBefore = file.length()
        Thread.sleep(STABLE_CHECK_DELAY_MS)
        val sizeAfter = file.length()
        if (sizeBefore != sizeAfter || sizeAfter == 0L) {
            // файл ещё пишется — попробуем ещё раз через паузу
            Thread.sleep(STABLE_CHECK_DELAY_MS)
        }

        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            overlay.showMessage("WA Voice Reader", "Новое голосовое сообщение, но не задан API-ключ OpenAI в настройках приложения.")
            return
        }

        // копируем файл с расширением .ogg — Whisper API распознаёт Ogg/Opus именно по этому расширению
        val tmp = File(cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        try {
            file.copyTo(tmp, overwrite = true)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось скопировать файл: ${e.message}")
            return
        }

        overlay.showMessage("WA Voice Reader", "Распознаю новое голосовое сообщение…", autoHideMs = 6000)

        val result = WhisperClient.transcribe(tmp, apiKey)
        tmp.delete()

        result.onSuccess { text ->
            val shown = if (text.isBlank()) "(пустая расшифровка)" else text
            overlay.showMessage("Голосовое сообщение", shown)
            updateNotification("Последнее сообщение распознано")
        }.onFailure { err ->
            Log.e(TAG, "Ошибка транскрибации: ${err.message}")
            overlay.showMessage("Ошибка распознавания", err.message ?: "неизвестная ошибка")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WA Voice Reader — слежение",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WA Voice Reader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
