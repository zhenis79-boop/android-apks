package com.genis.wavoicereader

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Отправляет аудиофайл в OpenAI Whisper API (endpoint /v1/audio/transcriptions)
 * и возвращает распознанный текст.
 */
object WhisperClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @param audioFile файл голосового сообщения (переименованный в .ogg, т.к. WhatsApp
     *                  использует Ogg/Opus контейнер, который Whisper понимает под расширением ogg)
     * @param apiKey    ключ OpenAI (Bearer)
     * @param language  необязательная подсказка языка, например "ru"
     */
    fun transcribe(audioFile: File, apiKey: String, language: String? = "ru"): Result<String> {
        return try {
            val mediaType = "audio/ogg".toMediaTypeOrNull()
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(mediaType)
                )
            if (!language.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("language", language)
            }

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(bodyBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}: $bodyStr"))
                }
                val json = JSONObject(bodyStr)
                val text = json.optString("text").trim()
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
