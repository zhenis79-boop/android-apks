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
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.Executors

class VoiceWatcherService : Service() {

    companion object {
        private const val TAG = "VoiceWatcher"
        private const val CHANNEL_ID = "wa_voice_watcher"
        private const val NOTIF_ID = 1
        private const val POLL_INTERVAL_MS = 3_000L
        private const val STABLE_CHECK_DELAY_MS = 900L

        // Возможные пути к папке голосовых сообщений WhatsApp на разных версиях Android/WA.
        // Голосовые внутри раскладываются по подпапкам-месяцам (напр. .../WhatsApp Voice Notes/202607/),
        // поэтому слежение ведётся рекурсивно.
        val CANDIDATE_DIRS = listOf(
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes",
            "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Voice Notes"
        )

        // Отдельная папка ТОЛЬКО для тестовой кнопки — никак не связана с WhatsApp.
        val TEST_DIR = "/storage/emulated/0/WAVoiceReaderTest/VoiceNotes"
    }

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileObservers = mutableListOf<FileObserver>()
    private val watchedDirPaths = mutableSetOf<String>()
    private var pollRunnable: Runnable? = null
    private lateinit var overlay: OverlayController

    private val rootDirs = mutableListOf<File>()
    private val knownFiles = mutableSetOf<String>()
    private var serviceStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        overlay = OverlayController(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Ожидание новых голосовых сообщений…"))
        serviceStartTime = System.currentTimeMillis()
        Logger.i(TAG, "Сервис запущен")
        if (!overlay.hasPermission()) {
            Logger.e(TAG, "ВНИМАНИЕ: нет разрешения 'поверх других приложений' — карточки не будут видны")
        }
        setupWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG, "Сервис остановлен")
        fileObservers.forEach { it.stopWatching() }
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        executor.shutdownNow()
    }

    private fun setupWatcher() {
        // 1. Реальные папки WhatsApp — берём все, что реально существуют на телефоне.
        val realDirs = CANDIDATE_DIRS.map { File(it) }.filter { it.exists() && it.isDirectory }

        // 2. Тестовая папка — создаём её всегда, чтобы тестовая кнопка работала без WhatsApp.
        val testDir = File(TEST_DIR)
        if (!testDir.exists()) testDir.mkdirs()

        rootDirs.clear()
        rootDirs.addAll((realDirs + testDir).distinctBy { it.absolutePath })

        // Диагностика в лог: какие папки WhatsApp найдены.
        if (realDirs.isEmpty()) {
            Logger.e(TAG, "Папка голосовых WhatsApp НЕ найдена. Проверьте: 1) выдан ли доступ ко всем файлам; " +
                "2) включена ли автозагрузка аудио в WhatsApp; 3) приходило ли хоть одно голосовое. " +
                "Проверенные пути: ${CANDIDATE_DIRS.joinToString(" | ")}")
        } else {
            Logger.i(TAG, "Найдены папки WhatsApp: ${realDirs.joinToString(" | ") { it.absolutePath }}")
        }

        // Рекурсивно вешаем наблюдатели на каждую папку и её подпапки.
        var subCount = 0
        for (root in rootDirs) {
            root.walkTopDown().filter { it.isDirectory }.forEach { dir ->
                if (attachObserver(dir)) subCount++
                // помечаем уже существующие файлы как известные, чтобы не распознавать старьё
                dir.listFiles()?.forEach { f -> if (f.isFile) knownFiles.add(f.absolutePath) }
            }
        }
        Logger.i(TAG, "Слежу за ${watchedDirPaths.size} папками (включая $subCount подпапок). Известных файлов: ${knownFiles.size}")

        startPolling()
        updateNotification("Слежу за голосовыми (${watchedDirPaths.size} папок)")
    }

    /** Вешает FileObserver на папку, если ещё не висит. Возвращает true, если добавлен новый. */
    @Suppress("DEPRECATION")
    private fun attachObserver(dir: File): Boolean {
        val path = dir.absolutePath
        if (path in watchedDirPaths) return false
        // CLOSE_WRITE/MOVED_TO — новый готовый файл; CREATE — может быть новая подпапка-месяц.
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        val obs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, childPath: String?) {
                    if (childPath != null) onFsEvent(dir, childPath)
                }
            }
        } else {
            object : FileObserver(path, mask) {
                override fun onEvent(event: Int, childPath: String?) {
                    if (childPath != null) onFsEvent(dir, childPath)
                }
            }
        }
        obs.startWatching()
        fileObservers.add(obs)
        watchedDirPaths.add(path)
        return true
    }

    private fun onFsEvent(dir: File, childPath: String) {
        val child = File(dir, childPath)
        if (child.isDirectory) {
            // Появилась новая подпапка (например, новый месяц) — начинаем следить и за ней.
            if (attachObserver(child)) {
                Logger.i(TAG, "Замечена новая подпапка, слежу за ней: ${child.absolutePath}")
            }
            return
        }
        handleCandidate(child)
    }

    /** Резервный опрос всех наблюдаемых папок рекурсивно на случай пропуска события. */
    private fun startPolling() {
        pollRunnable = object : Runnable {
            override fun run() {
                for (root in rootDirs) {
                    root.walkTopDown().forEach { f ->
                        if (f.isDirectory) {
                            if (attachObserver(f)) {
                                Logger.i(TAG, "Опрос: подключил новую подпапку ${f.absolutePath}")
                            }
                        } else {
                            handleCandidate(f)
                        }
                    }
                }
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(pollRunnable!!, POLL_INTERVAL_MS)
    }

    private fun handleCandidate(file: File) {
        if (!file.exists() || !file.isFile) return
        if (file.absolutePath in knownFiles) return

        val lname = file.name.lowercase()
        if (!lname.endsWith(".opus") && !lname.endsWith(".ogg")) {
            knownFiles.add(file.absolutePath) // чтобы не логировать один и тот же файл повторно
            return
        }
        if (file.lastModified() < serviceStartTime - 5_000) {
            knownFiles.add(file.absolutePath)
            Logger.i(TAG, "Пропущен старый файл (создан до запуска сервиса): ${file.name}")
            return
        }

        knownFiles.add(file.absolutePath)
        Logger.i(TAG, "Новый голосовой файл: ${file.absolutePath} (${file.length()} байт)")
        executor.execute { processFile(file) }
    }

    private fun processFile(file: File) {
        // ждём, пока WhatsApp закончит запись файла (размер должен стабилизироваться)
        var sizeBefore = file.length()
        Thread.sleep(STABLE_CHECK_DELAY_MS)
        var sizeAfter = file.length()
        var tries = 0
        while (sizeBefore != sizeAfter && tries < 5) {
            sizeBefore = sizeAfter
            Thread.sleep(STABLE_CHECK_DELAY_MS)
            sizeAfter = file.length()
            tries++
        }
        if (sizeAfter == 0L) {
            Logger.e(TAG, "Файл пустой, пропускаю: ${file.name}")
            return
        }

        // Определяем отправителя и отсеиваем ВАШИ исходящие голосовые.
        val isTest = file.absolutePath.startsWith(TEST_DIR)
        val title: String
        if (isTest) {
            title = "Тест (эмуляция)"
        } else if (NotificationAccess.isEnabled(this)) {
            val rec = SenderTracker.matchIncomingVoice(file.lastModified())
            if (rec == null) {
                Logger.i(TAG, "Пропущено: нет входящего голосового уведомления WhatsApp — похоже на ваше исходящее (${file.name})")
                return
            }
            title = "Голосовое от ${rec.title}"
        } else {
            title = "Голосовое сообщение"
            Logger.i(TAG, "Доступ к уведомлениям выключен — не могу определить отправителя и отсеять исходящие. Включите его в приложении.")
        }

        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            Logger.e(TAG, "Не задан API-ключ OpenAI — распознавание невозможно")
            overlay.showMessage("WA Voice Reader", "Новое голосовое, но не задан API-ключ OpenAI в настройках приложения.")
            return
        }

        // копируем файл с расширением .ogg — Whisper API распознаёт Ogg/Opus по расширению
        val tmp = File(cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        try {
            file.copyTo(tmp, overwrite = true)
        } catch (e: Exception) {
            Logger.e(TAG, "Не удалось скопировать файл ${file.name}", e)
            return
        }

        overlay.showMessage(title, "Распознаю…", autoHideMs = 8000)
        Logger.i(TAG, "Отправляю на распознавание [$title]: ${tmp.name} (${tmp.length()} байт)")

        val result = WhisperClient.transcribe(tmp, apiKey)
        tmp.delete()

        result.onSuccess { text ->
            val shown = if (text.isBlank()) "(пустая расшифровка)" else text
            Logger.i(TAG, "Распознано [$title]: $shown")
            HistoryStore.add(this, "$title: $shown")
            overlay.showMessage(title, shown)
            updateNotification("Последнее сообщение распознано")
        }.onFailure { err ->
            Logger.e(TAG, "Ошибка транскрибации", err)
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
