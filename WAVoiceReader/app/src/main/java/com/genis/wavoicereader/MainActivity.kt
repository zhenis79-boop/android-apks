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
                Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAllFilesAccess.setOnClickListener { requestAllFilesAccess() }
        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnNotifPermission.setOnClickListener { requestNotificationPermission() }

        binding.btnStart.setOnClickListener {
            Prefs.setServiceEnabled(this, true)
            Logger.i("MainActivity", "Пользователь нажал 'Запустить слежение'")
            val intent = Intent(this, VoiceWatcherService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Сервис запущен", Toast.LENGTH_SHORT).show()
        }

        binding.btnStop.setOnClickListener {
            Prefs.setServiceEnabled(this, false)
            Logger.i("MainActivity", "Пользователь нажал 'Остановить'")
            stopService(Intent(this, VoiceWatcherService::class.java))
            Toast.makeText(this, "Сервис остановлен", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestRecord.setOnClickListener { onTestButtonClicked() }

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
            Logger.clear(this)
            refreshLogs()
            Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
        }

        updateStatusText()
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
        Logger.i("MainActivity", "Тестовый файл записан: ${testFile?.absolutePath}")
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

    override fun onResume() {
        super.onResume()
        updateStatusText()
        refreshHistory()
        refreshLogs()
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

    private fun updateStatusText() {
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager() else true
        val overlay = Settings.canDrawOverlays(this)
        val key = !Prefs.getApiKey(this).isNullOrBlank()

        binding.textStatus.text = buildString {
            append("Доступ ко всем файлам: ${if (allFiles) "✅" else "❌"}\n")
            append("Показ поверх экрана: ${if (overlay) "✅" else "❌"}\n")
            append("API-ключ сохранён: ${if (key) "✅" else "❌"}")
        }
    }
}
