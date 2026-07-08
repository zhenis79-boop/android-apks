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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class VoiceWatcherService : Service() {

    companion object {
        private const val TAG = "VoiceWatcher"
        private const val CHANNEL_ID = "wa_voice_watcher"
        private const val NOTIF_ID = 1
        // Опрос — только подстраховка на случай пропуска системного события, поэтому редкий.
        private const val POLL_INTERVAL_MS = 20_000L
        private const val STABLE_CHECK_DELAY_MS = 900L
        // Файл иногда обгоняет уведомление WhatsApp на доли секунды — даём немного времени
        // подождать. Раз мы теперь всё равно распознаём и без совпадения (см. processFile),
        // долго ждать не нужно: если уведомления не будет вообще, не стоит тормозить весь
        // ответ ради этого — лучше показать текст без имени быстрее.
        private const val SENDER_MATCH_RETRY_MS = 500L
        private const val SENDER_MATCH_MAX_WAIT_MS = 1_500L

        // Возможные пути к папке голосовых сообщений WhatsApp на разных версиях Android/WA.
        // Голосовые внутри раскладываются по подпапкам-месяцам (напр. .../WhatsApp Voice Notes/202607/) —
        // слушаем саму папку (чтобы поймать появление новой месячной подпапки) и её содержимое
        // за последние 2 месяца, см. recentMonthNames().
        val CANDIDATE_DIRS = listOf(
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes",
            "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Voice Notes"
        )

        // Отдельная папка ТОЛЬКО для тестовой кнопки — никак не связана с WhatsApp.
        val TEST_DIR = "/storage/emulated/0/WAVoiceReaderTest/VoiceNotes"
    }

    // Файлы обрабатываются по одному — параллелизм тут не нужен, а один поток экономит память.
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileObservers = mutableListOf<FileObserver>()
    private val watchedDirPaths = mutableSetOf<String>()
    // Для опроса-подстраховки: не пересканировать папку, если её mtime не менялся с прошлого раза.
    private val dirLastModified = mutableMapOf<String, Long>()
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
        setupWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fileObservers.forEach { it.stopWatching() }
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        executor.shutdownNow()
    }

    /** Имена подпапок-месяцев (формат YYYYMM), которые имеет смысл слушать — текущий и предыдущий. */
    private fun recentMonthNames(): Set<String> {
        val fmt = SimpleDateFormat("yyyyMM", Locale.US)
        val cal = Calendar.getInstance()
        val cur = fmt.format(cal.time)
        cal.add(Calendar.MONTH, -1)
        val prev = fmt.format(cal.time)
        return setOf(cur, prev)
    }

    private fun setupWatcher() {
        // 1. Реальные папки WhatsApp — берём все, что реально существуют на телефоне.
        val realDirs = CANDIDATE_DIRS.map { File(it) }.filter { it.exists() && it.isDirectory }

        // 2. Тестовая папка — создаём её всегда, чтобы тестовая кнопка работала без WhatsApp.
        val testDir = File(TEST_DIR)
        if (!testDir.exists()) testDir.mkdirs()

        rootDirs.clear()
        rootDirs.addAll((realDirs + testDir).distinctBy { it.absolutePath })

        if (realDirs.isEmpty()) {
            Logger.e(TAG, "Папки WhatsApp НЕ найдены. Проверенные пути: ${CANDIDATE_DIRS.joinToString(" | ")}")
        } else {
            Logger.i(TAG, "Найдены папки WhatsApp: ${realDirs.joinToString(" | ") { it.absolutePath }}")
        }

        // Новые голосовые всегда попадают в папку текущего месяца, поэтому вглубь смотрим только
        // на неё и на прошлый месяц (на случай, если сообщение пришло прямо на границе месяцев) —
        // остальные десятки старых папок с историей нам не нужны и только тратят ресурсы.
        val recentMonths = recentMonthNames()
        for (root in rootDirs) {
            attachObserver(root) // сама корневая папка — чтобы поймать CREATE новой месячной подпапки
            root.listFiles()?.forEach { entry ->
                when {
                    entry.isFile -> knownFiles.add(entry.absolutePath)
                    entry.isDirectory && (root == testDir || entry.name in recentMonths) -> {
                        attachObserver(entry)
                        entry.listFiles()?.forEach { f -> if (f.isFile) knownFiles.add(f.absolutePath) }
                    }
                }
            }
        }
        Logger.i(TAG, "Слежу за ${watchedDirPaths.size} папками, известных файлов: ${knownFiles.size}")

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
            attachObserver(child)
            return
        }
        handleCandidate(child)
    }

    /**
     * Резервный опрос на случай пропуска системного события. Дешёвый: полностью пересканирует
     * содержимое папки, только если её mtime изменился с прошлой проверки (появился/пропал файл) —
     * иначе для неё делается один быстрый stat() вместо чтения списка файлов.
     */
    private fun startPolling() {
        pollRunnable = object : Runnable {
            override fun run() {
                for (path in watchedDirPaths.toList()) {
                    val dir = File(path)
                    val mtime = dir.lastModified()
                    if (dirLastModified[path] == mtime) continue
                    dirLastModified[path] = mtime
                    dir.listFiles()?.forEach { f ->
                        if (f.isDirectory) attachObserver(f) else handleCandidate(f)
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
            knownFiles.add(file.absolutePath)
            return
        }
        if (file.lastModified() < serviceStartTime - 5_000) {
            knownFiles.add(file.absolutePath)
            return
        }

        knownFiles.add(file.absolutePath)
        Logger.i(TAG, "Новый файл-кандидат: ${file.absolutePath} (${file.length()} байт)")
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
        if (sizeAfter == 0L) return

        // Определяем отправителя и отсеиваем ВАШИ исходящие голосовые.
        // Без активного доступа к уведомлениям отличить входящее от исходящего невозможно —
        // в этом случае НЕ распознаём вообще (безопасный дефолт), а не молча обрабатываем всё подряд.
        val isTest = file.absolutePath.startsWith(TEST_DIR)
        val title: String
        if (isTest) {
            title = "Тест (эмуляция)"
        } else if (!NotificationAccess.isEnabled(this)) {
            Logger.e(TAG, "Доступ к уведомлениям выключен — пропускаю файл ${file.name}")
            overlay.showMessage(
                "WA Voice Reader",
                "Голосовое не распознано: включите «Доступ к уведомлениям» в приложении, иначе нельзя отличить входящее от вашего исходящего."
            )
            return
        } else {
            // Файл иногда появляется чуть РАНЬШЕ уведомления WhatsApp (доли секунды —
            // единицы секунд), поэтому пробуем сопоставить несколько раз с паузами,
            // а не один раз сразу — иначе ловим гонку и теряем настоящие входящие.
            var rec: SenderTracker.Rec? = null
            var waitedMs = 0L
            while (rec == null && waitedMs <= SENDER_MATCH_MAX_WAIT_MS) {
                rec = SenderTracker.matchIncomingVoice(file.lastModified())
                if (rec == null) {
                    Thread.sleep(SENDER_MATCH_RETRY_MS)
                    waitedMs += SENDER_MATCH_RETRY_MS
                }
            }
            if (rec == null) {
                // WhatsApp не всегда шлёт уведомление (замьюченный чат, DND, открытый экран) —
                // в этом случае лучше распознать без имени отправителя, чем молча пропустить
                // настоящее входящее сообщение. Риск изредка увидеть своё исходящее — меньшее зло.
                Logger.i(TAG, "Нет совпадения с уведомлением для ${file.name} — распознаю без имени отправителя")
                title = "Голосовое сообщение"
            } else {
                title = "Голосовое от ${rec.title}"
                Logger.i(TAG, "Совпадение найдено: \"${rec.title}\"")
            }
        }

        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            overlay.showMessage("WA Voice Reader", "Новое голосовое, но не задан API-ключ OpenAI в настройках приложения.")
            return
        }

        // копируем файл с расширением .ogg — Whisper API распознаёт Ogg/Opus по расширению
        val tmp = File(cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        try {
            file.copyTo(tmp, overwrite = true)
        } catch (e: Exception) {
            return
        }

        val result = WhisperClient.transcribe(tmp, apiKey)
        tmp.delete()

        result.onSuccess { text ->
            val shown = if (text.isBlank()) "(пустая расшифровка)" else text
            HistoryStore.add(this, "$title: $shown")
            overlay.showMessage(title, shown)
            updateNotification("Последнее сообщение распознано")
        }.onFailure { err ->
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
