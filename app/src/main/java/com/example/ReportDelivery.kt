package com.example

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * تنظیمات ارسال گزارش‌ها به بیرون از برنامه.
 * تلگرام از طریق ربات و گوگل درایو از طریق آدرس اسکریپت گوگل (Apps Script) انجام می‌شود.
 */
data class CloudSettings(
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val driveUploadUrl: String = "",
    val driveSecret: String = ""
) {
    val isTelegramReady: Boolean
        get() = telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()

    val isDriveReady: Boolean
        get() = driveUploadUrl.isNotBlank()

    val isAudioArchiveReady: Boolean
        get() = isTelegramReady && isDriveReady
}

object AppSettingsStore {
    private const val PREFS_NAME = "finto_settings"
    private const val KEY_BOT_TOKEN = "telegram_bot_token"
    private const val KEY_CHAT_ID = "telegram_chat_id"
    private const val KEY_DRIVE_URL = "drive_upload_url"
    private const val KEY_DRIVE_SECRET = "drive_secret"

    fun load(context: Context): CloudSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CloudSettings(
            telegramBotToken = prefs.getString(KEY_BOT_TOKEN, "").orEmpty(),
            telegramChatId = prefs.getString(KEY_CHAT_ID, "").orEmpty(),
            driveUploadUrl = prefs.getString(KEY_DRIVE_URL, "").orEmpty(),
            driveSecret = prefs.getString(KEY_DRIVE_SECRET, "").orEmpty()
        )
    }

    fun save(context: Context, settings: CloudSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_BOT_TOKEN, settings.telegramBotToken.trim())
            .putString(KEY_CHAT_ID, settings.telegramChatId.trim())
            .putString(KEY_DRIVE_URL, settings.driveUploadUrl.trim())
            .putString(KEY_DRIVE_SECRET, settings.driveSecret.trim())
            .apply()
    }
}

class DeliveryException(message: String) : Exception(message)

class ReportDelivery(context: Context) {

    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    fun currentSettings(): CloudSettings = AppSettingsStore.load(appContext)

    /** ارسال متن به تلگرام؛ شناسه پیام ارسالی برگردانده می‌شود. */
    fun sendTextToTelegram(text: String): String {
        val settings = currentSettings()
        if (!settings.isTelegramReady) throw DeliveryException("تنظیمات تلگرام کامل نیست")

        val body = FormBody.Builder()
            .add("chat_id", settings.telegramChatId)
            .add("text", text)
            .add("disable_web_page_preview", "true")
            .build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot${settings.telegramBotToken}/sendMessage")
            .post(body)
            .build()
        val json = executeTelegram(request, "sendMessage")
        return json.optJSONObject("result")?.optLong("message_id")?.toString() ?: "sent"
    }

    /** ارسال فایل (صوت یا متن) به تلگرام؛ شناسه پیام ارسالی برگردانده می‌شود. */
    fun sendFileToTelegram(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        caption: String,
        preferAudio: Boolean = false
    ): String {
        val settings = currentSettings()
        if (!settings.isTelegramReady) throw DeliveryException("تنظیمات تلگرام کامل نیست")

        if (preferAudio) {
            val audioJson = runCatching {
                executeTelegram(
                    buildTelegramFileRequest(settings, "sendAudio", "audio", fileName, mimeType, bytes, caption),
                    "sendAudio"
                )
            }.getOrNull()
            if (audioJson != null) {
                return audioJson.optJSONObject("result")?.optLong("message_id")?.toString() ?: "sent"
            }
        }

        val json = executeTelegram(
            buildTelegramFileRequest(settings, "sendDocument", "document", fileName, mimeType, bytes, caption),
            "sendDocument"
        )
        return json.optJSONObject("result")?.optLong("message_id")?.toString() ?: "sent"
    }

    /** بارگذاری فایل روی گوگل درایو از طریق اسکریپت گوگل؛ لینک فایل برگردانده می‌شود. */
    fun uploadToGoogleDrive(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        description: String
    ): String {
        val settings = currentSettings()
        if (!settings.isDriveReady) throw DeliveryException("تنظیمات گوگل درایو کامل نیست")

        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("secret", settings.driveSecret)
            .addFormDataPart("fileName", fileName)
            .addFormDataPart("mimeType", mimeType)
            .addFormDataPart("description", description)
            .addFormDataPart("contentBase64", encoded)
            .build()
        val request = Request.Builder()
            .url(settings.driveUploadUrl)
            .post(body)
            .build()

        val raw = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DeliveryException("گوگل درایو پاسخ ${response.code} برگرداند")
                }
                response.body?.string().orEmpty()
            }
        } catch (exception: DeliveryException) {
            throw exception
        } catch (exception: Exception) {
            throw DeliveryException("ارتباط با گوگل درایو برقرار نشد")
        }

        val json = try {
            JSONObject(raw.trim())
        } catch (exception: Exception) {
            throw DeliveryException("پاسخ اسکریپت گوگل قابل خواندن نبود")
        }
        if (json.optString("status") != "ok") {
            throw DeliveryException(
                "گوگل درایو: " + json.optString("message", "ذخیره فایل انجام نشد")
            )
        }
        return json.optString("url", "ذخیره شد")
    }

    fun uploadTextToGoogleDrive(fileName: String, text: String, description: String): String =
        uploadToGoogleDrive(
            fileName = fileName,
            mimeType = "text/plain",
            bytes = text.toByteArray(Charsets.UTF_8),
            description = description
        )

    private fun buildTelegramFileRequest(
        settings: CloudSettings,
        method: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        caption: String
    ): Request {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", settings.telegramChatId)
            .addFormDataPart("caption", caption.take(1000))
            .addFormDataPart("title", caption.take(60))
            .addFormDataPart(
                fieldName,
                fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            )
        return Request.Builder()
            .url("https://api.telegram.org/bot${settings.telegramBotToken}/$method")
            .post(builder.build())
            .build()
    }

    private fun executeTelegram(request: Request, methodName: String): JSONObject {
        val raw = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw DeliveryException("تلگرام: ${telegramErrorMessage(text, response.code)}")
                }
                text
            }
        } catch (exception: DeliveryException) {
            throw exception
        } catch (exception: Exception) {
            throw DeliveryException("ارتباط با تلگرام در $methodName برقرار نشد")
        }

        val json = try {
            JSONObject(raw.trim())
        } catch (exception: Exception) {
            throw DeliveryException("پاسخ تلگرام قابل خواندن نبود")
        }
        if (!json.optBoolean("ok")) {
            throw DeliveryException("تلگرام: " + json.optString("description", "ارسال انجام نشد"))
        }
        return json
    }

    private fun telegramErrorMessage(raw: String, code: Int): String {
        val description = runCatching {
            JSONObject(raw.trim()).optString("description")
        }.getOrNull()
        return if (description.isNullOrBlank()) "خطای $code" else description
    }
}

/** ضبط گزارش صوتی در پوشه موقت برنامه (کش) و پاک کردن آن پس از ارسال موفق. */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMillis = 0L

    val isRecording: Boolean
        get() = recorder != null

    val elapsedMillis: Long
        get() = if (startedAtMillis == 0L) 0L else System.currentTimeMillis() - startedAtMillis

    @Suppress("DEPRECATION")
    fun start(): File {
        cancel()
        val directory = File(context.cacheDir, "voice_reports")
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, "voice_${System.currentTimeMillis()}.m4a")

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder.setAudioEncodingBitRate(96_000)
        mediaRecorder.setAudioSamplingRate(44_100)
        mediaRecorder.setOutputFile(file.absolutePath)
        mediaRecorder.prepare()
        mediaRecorder.start()

        recorder = mediaRecorder
        outputFile = file
        startedAtMillis = System.currentTimeMillis()
        return file
    }

    fun stop(): File? {
        val mediaRecorder = recorder ?: return null
        recorder = null
        startedAtMillis = 0L
        val file = outputFile
        return try {
            mediaRecorder.stop()
            mediaRecorder.release()
            file?.takeIf { it.exists() && it.length() > 0 }
        } catch (exception: Exception) {
            runCatching { mediaRecorder.release() }
            file?.delete()
            null
        }
    }

    fun cancel() {
        val mediaRecorder = recorder
        recorder = null
        startedAtMillis = 0L
        if (mediaRecorder != null) {
            runCatching { mediaRecorder.stop() }
            runCatching { mediaRecorder.release() }
        }
        outputFile?.delete()
        outputFile = null
    }
}
