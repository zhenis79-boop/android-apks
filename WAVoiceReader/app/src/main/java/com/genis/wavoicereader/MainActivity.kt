package com.genis.wavoicereader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.genis.wavoicereader.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // Пока экран открыт — обновляем историю/логи сами, не дожидаясь возврата в приложение.
        private const val AUTO_REFRESH_INTERVAL_MS = 4_000L
    }

    private lateinit var binding: ActivityMainBinding
    private var testRecorder: MediaRecorder? = null
    private var testFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Prefs.getApiKey(this)?.let { binding.editApiKey.setText(it) }

        binding.btnSaveKey.setOnClickListener {
            val key = binding.editApiKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Введите API-ключ", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.setApiKey(this, key)
                Logger.i("MainActivity", "API-ключ сохранён (${key.length} символов)")
                Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAllFilesAccess.setOnClickListener { requestAllFilesAccess() }
        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnNotifPermission.setOnClickListener { requestNotificationPermission() }
        binding.btnNotifAccess.setOnClickListener { requestNotificationAccess() }

        binding.btnStart.setOnClickListener {
            Prefs.setServiceEnabled(this, true)
            // Сбрасываем счётчики цепочки, чтобы статистика отражала именно эту сессию
            // слежения, а не «с момента запуска приложения».
            PipelineStats.reset()
            val intent = Intent(this, VoiceWatcherService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Logger.i("MainActivity", "Слежение включено пользователем")
            Toast.makeText(this, "Сервис запущен", Toast.LENGTH_SHORT).show()
            updateStatusText()
            refreshPipeline()
        }

        binding.btnStop.setOnClickListener {
            Prefs.setServiceEnabled(this, false)
            stopService(Intent(this, VoiceWatcherService::class.java))
            Logger.i("MainActivity", "Слежение остановлено пользователем")
            Toast.makeText(this, "Сервис остановлен", Toast.LENGTH_SHORT).show()
            updateStatusText()
        }

        binding.btnResetStats.setOnClickListener {
            PipelineStats.reset()
            refreshPipeline()
            Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestRecord.setOnClickListener { onTestButtonClicked() }

        // Клик по красному баннеру предупреждения ведёт прямо в нужные системные настройки:
        // если это зомби-listener — на экран доступа к уведомлениям (там переключить тумблер).
        binding.textWarning.setOnClickListener { openWarningTarget() }

        binding.btnCopyHistory.setOnClickListener {
            copyToClipboard("История WA Voice Reader", HistoryStore.formatted(this))
        }
        binding.btnClearHistory.setOnClickListener {
            HistoryStore.clear(this)
            refreshHistory()
            Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyLogs.setOnClickListener {
            copyToClipboard("Логи WA Voice Reader", Logger.read())
        }
        binding.btnRefreshLogs.setOnClickListener { refreshLogs() }
        binding.btnClearLogs.setOnClickListener {
            Logger.clear()
            refreshLogs()
            Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
        }

        updateStatusText()
        updateWarning()
        refreshPipeline()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    private fun refreshHistory() {
        binding.textHistory.text = HistoryStore.formatted(this)
    }

    private fun refreshLogs() {
        binding.textLogs.text = Logger.read()
    }

    /**
     * Обновляет живую сводку цепочки обработки. Читается в авто-обновлении (каждые
     * AUTO_REFRESH_INTERVAL_MS), чтобы числа и «Nс назад» шли вживую без ручного
     * обновления. Помогает быстро понять, на каком этапе что-то застряло.
     */
    private fun refreshPipeline() {
        val s = PipelineStats.snapshot()
        val now = System.currentTimeMillis()
        binding.textPipeline.text = buildString {
            append("Файлов поймано:      ${s.candidatesCaught}  (${PipelineStats.agoLabel(s.lastCandidateAt, now)})\n")
            append("Задержка ловли:      ${PipelineStats.lagLabel(s.lastCatchLagMs)}\n")
            append("WA-уведомлений:      ${s.waNotifications}  (голосовых:${s.waVoiceNotifications} прочих:${s.waOtherNotifications})  (${PipelineStats.agoLabel(s.lastNotificationAt, now)})\n")
            append("Match отправителя:   ✅${s.senderMatched}  без имени:${s.senderUnmatched}  (${PipelineStats.agoLabel(s.lastSenderDecisionAt, now)})\n")
            append("Распознаваний:       ✅${s.transcribeOk}  ❌${s.transcribeError}  (${PipelineStats.agoLabel(s.lastTranscribeAt, now)})\n")
            append("Время обработки:     ${PipelineStats.lagLabel(s.lastProcessLagMs)}")
        }
    }

    private fun onTestButtonClicked() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
            return
        }
        startTestRecording()
    }

    /**
     * Записывает короткую тестовую фразу с микрофона и кладёт её в ту же папку,
     * которую слушает VoiceWatcherService — так, будто это настоящее голосовое
     * сообщение WhatsApp. Позволяет проверить всю цепочку (обнаружение файла →
     * распознавание → всплывающее окно) без реального WhatsApp и без ожидания
     * входящего сообщения.
     */
    private fun startTestRecording() {
        val dir = findOrCreateWatchedDir()
        if (dir == null) {
            Toast.makeText(this, "Не удалось создать тестовую папку. Проверьте разрешение на доступ к файлам.", Toast.LENGTH_LONG).show()
            return
        }

        val fileName = "TEST_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".opus"
        val outFile = File(dir, fileName)
        testFile = outFile

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            } else {
                // На старых версиях Android нет кодера Opus — пишем в 3GP/AMR.
                // Расширение .opus всё равно оставляем, чтобы файл поймал наблюдатель;
                // для реального теста распознавания лучше использовать Android 10+.
                recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            }
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()
            recorder.start()
            testRecorder = recorder

            Toast.makeText(this, "🎤 Говорите 3 секунды — записываю тестовое голосовое…", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ stopTestRecording() }, 3000)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка записи: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopTestRecording() {
        try {
            testRecorder?.stop()
        } catch (_: Exception) {
        }
        testRecorder?.release()
        testRecorder = null

        val dir = testFile?.parentFile
        Toast.makeText(
            this,
            "Готово! Файл ${testFile?.name} создан в папке ${dir?.name}.\n" +
                "Если сервис слежения запущен — через несколько секунд появится всплывающее окно с текстом.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Возвращает отдельную тестовую папку (VoiceWatcherService.TEST_DIR), создавая её
     * при необходимости. Эта папка никак не связана с WhatsApp — она нужна только для
     * тестовой кнопки, чтобы проверять всю цепочку даже без установленного WhatsApp.
     */
    private fun findOrCreateWatchedDir(): File? {
        val dir = File(VoiceWatcherService.TEST_DIR)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    private val autoRefreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            updateStatusText()
            updateWarning()
            refreshPipeline()
            refreshHistory()
            refreshLogs()
            autoRefreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        autoRefreshHandler.post(autoRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Toast.makeText(this, "Доступ уже разрешён", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Разрешение уже выдано", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun requestNotificationAccess() {
        // Всегда открываем экран настроек, даже если разрешение уже отмечено выданным:
        // если приложение не получает реальные уведомления, нужно выключить и включить
        // тумблер заново — а для этого нужно попасть на этот экран, а не увидеть тост.
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            val hint = if (NotificationAccess.isEnabled(this))
                "Найдите «WA Voice Reader» — выключите тумблер и сразу включите обратно (это чинит зависший доступ)"
            else
                "Найдите «WA Voice Reader» в списке и включите доступ"
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть настройки доступа к уведомлениям", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startTestRecording()
        } else if (requestCode == 200) {
            Toast.makeText(this, "Без разрешения на микрофон тест недоступен", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Показывает громкое красное предупреждение в самом верху, ТОЛЬКО когда найдено
     * конкретное опасное состояние. Сейчас это «зомби-listener»: системный тумблер
     * «Доступ к уведомлениям» включён, но слушатель ни разу реально не подключился и
     * колбэков не получает — имя отправителя определяться не будет. Это самая частая
     * причина «всё работает, но имени нет», и она повторяется после перезагрузки/обновления APK.
     *
     * Баннер показываем только когда слежение включено: если пользователь ещё не
     * запустил сервис — предупреждать о listener рано.
     */
    private fun updateWarning() {
        val notifAccess = NotificationAccess.isEnabled(this)
        val connectedAt = Prefs.getNotifListenerConnectedAt(this)
        val serviceEnabled = Prefs.isServiceEnabled(this)

        val zombieListener = notifAccess && connectedAt <= 0L && serviceEnabled

        if (!zombieListener) {
            binding.textWarning.visibility = android.view.View.GONE
            return
        }

        binding.textWarning.visibility = android.view.View.VISIBLE
        binding.textWarning.text =
            "⚠️ Имена отправителей не определяются: доступ к уведомлениям завис.\n" +
            "Нажмите здесь, откройте «WA Voice Reader», выключите тумблер и сразу включите обратно."
    }

    /** Куда вести пользователя по клику на баннер — сейчас всегда на доступ к уведомлениям. */
    private fun openWarningTarget() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusText() {
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager() else true
        val overlay = Settings.canDrawOverlays(this)
        val notifAccess = NotificationAccess.isEnabled(this)
        val key = !Prefs.getApiKey(this).isNullOrBlank()
        val connectedAt = Prefs.getNotifListenerConnectedAt(this)
        val serviceEnabled = Prefs.isServiceEnabled(this)
        val realDirs = VoiceWatcherService.CANDIDATE_DIRS.map { File(it) }
            .filter { it.exists() && it.isDirectory }
        val testDir = File(VoiceWatcherService.TEST_DIR)
        val historyCount = HistoryStore.all(this).size
        val logLines = Logger.read().lineSequence()
            .count { it.isNotBlank() && !it.startsWith("(") }

        binding.textStatus.text = buildString {
            append("Доступ ко всем файлам: ${if (allFiles) "✅" else "❌"}\n")
            append("Показ поверх экрана: ${if (overlay) "✅" else "❌"}\n")
            append("Доступ к уведомлениям (разрешение): ${if (notifAccess) "✅" else "❌"}\n")
            if (notifAccess) {
                if (connectedAt > 0) {
                    val ago = (System.currentTimeMillis() - connectedAt) / 1000
                    append("Слушатель уведомлений реально подключался: ✅ (${ago}с назад)\n")
                } else {
                    append("Слушатель уведомлений реально подключался: ❌ (ни разу! переключите доступ выкл/вкл в настройках)\n")
                }
            }
            append("API-ключ сохранён: ${if (key) "✅" else "❌"}\n")
            append("Слежение включено в настройках: ${if (serviceEnabled) "✅" else "❌"}\n")
            append("Папки WhatsApp найдены: ${if (realDirs.isNotEmpty()) "✅ (${realDirs.size})" else "❌"}\n")
            if (realDirs.isNotEmpty()) {
                realDirs.forEach { dir -> append("• ${dir.absolutePath}\n") }
            } else {
                append("• проверено путей: ${VoiceWatcherService.CANDIDATE_DIRS.size}\n")
            }
            append("Тестовая папка: ${if (testDir.exists() && testDir.isDirectory) "✅" else "❌"} ${testDir.absolutePath}\n")
            append("История: $historyCount записей\n")
            append("Логи: $logLines строк")
        }
    }
}
