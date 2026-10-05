package com.naor.expensetracker

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var lastMessageHash = 0
    private var lastMessageTime = 0L

    companion object {
        private const val TAG = "ExpenseTrackerListener"

        // Target application packages (Google Wallet, Google Pay, Israeli cards)
        private val TARGET_PACKAGES = setOf(
            "com.google.android.apps.walletnfcrel", // Google Wallet
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.google.android.gms", // Google Play services
            "com.max.app", // Max it
            "com.isracard", // Isracard
            "com.cal.app", // Cal
            "com.bankhapoalim.bpay", // Poalim
            "com.leumi.leumicard" // Leumi
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: ""
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val body = if (bigText.isNotBlank()) bigText else text
        if (body.isBlank() && title.isBlank()) return

        val fullText = "$title $body".trim()

        // Check if from target package or contains payment keywords
        val isTargetApp = TARGET_PACKAGES.contains(packageName)
        val hasPaymentKeyword = fullText.contains("שילמת") ||
                fullText.contains("חויבת") ||
                fullText.contains("עסקה") ||
                fullText.contains("₪") ||
                fullText.contains("Paid") ||
                fullText.contains("payment") ||
                fullText.contains("Google Pay") ||
                fullText.contains("Wallet")

        if (!isTargetApp && !hasPaymentKeyword) {
            return
        }

        // Deduplication (ignore identical notification within 15 seconds)
        val now = System.currentTimeMillis()
        val currentHash = fullText.hashCode()
        if (currentHash == lastMessageHash && (now - lastMessageTime) < 15_000) {
            Log.d(TAG, "Skipping duplicate notification: $fullText")
            return
        }
        lastMessageHash = currentHash
        lastMessageTime = now

        Log.i(TAG, "Forwarding payment notification: $fullText (from $packageName)")

        scope.launch {
            forwardToWebhook(fullText, packageName)
        }
    }

    private fun forwardToWebhook(notificationText: String, sourceApp: String) {
        val webhookUrl = Prefs.getWebhookUrl(applicationContext)

        try {
            val jsonPayload = JSONObject().apply {
                put("text", notificationText)
                put("source", "android_listener")
                put("app", sourceApp)
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(webhookUrl)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Log.i(TAG, "Successfully sent to webhook: $responseBody")
                Prefs.addLog(applicationContext, "✅ נקלט: $notificationText (${response.code})")
            } else {
                Log.w(TAG, "Webhook returned error: ${response.code} - $responseBody")
                Prefs.addLog(applicationContext, "⚠️ שגיאה ${response.code}: $notificationText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send webhook request", e)
            Prefs.addLog(applicationContext, "❌ נכשל בשליחה: ${e.message}")
        }
    }
}
