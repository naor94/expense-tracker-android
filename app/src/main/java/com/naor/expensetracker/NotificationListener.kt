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

        // STRICT: Only Google Wallet & Google Pay packages
        private val GOOGLE_WALLET_PACKAGES = setOf(
            "com.google.android.apps.walletnfcrel", // Google Wallet official app
            "com.google.android.apps.nbu.paisa.user" // Google Pay
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: ""

        // NEVER process WhatsApp, Telegram, SMS, or any non-Google Wallet app
        val isWalletPackage = GOOGLE_WALLET_PACKAGES.contains(packageName)
        val isGooglePlayPayment = packageName == "com.google.android.gms" &&
                (sbn.notification?.extras?.getString(Notification.EXTRA_TITLE)?.contains("Google Pay") == true ||
                 sbn.notification?.extras?.getString(Notification.EXTRA_TITLE)?.contains("Wallet") == true)

        if (!isWalletPackage && !isGooglePlayPayment) {
            return
        }

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val body = if (bigText.isNotBlank()) bigText else text
        if (body.isBlank() && title.isBlank()) return

        val fullText = "$title $body".trim()

        // Verify it contains a payment indicator (e.g. "שילמת", "₪", "Paid")
        val hasPaymentIndicator = fullText.contains("שילמת") ||
                fullText.contains("חויבת") ||
                fullText.contains("₪") ||
                fullText.contains("Paid") ||
                fullText.contains("ILS") ||
                fullText.contains("NIS")

        if (!hasPaymentIndicator) {
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
