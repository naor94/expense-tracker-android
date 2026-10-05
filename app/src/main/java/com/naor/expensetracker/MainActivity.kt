package com.naor.expensetracker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.naor.expensetracker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebhookSettings()
        setupTestButton()
        setupLogs()

        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshLogs()
    }

    private fun updatePermissionStatus() {
        val isEnabled = isNotificationServiceEnabled()

        if (isEnabled) {
            binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.success)
            binding.tvStatusIcon.text = "🟢"
            binding.tvStatusTitle.text = "השירות פעיל ומאזין לתשלומים"
            binding.tvStatusDesc.text = "כל תשלום שיתבצע ב-Google Wallet או באשראי יועבר מיד ללוח הבקרה שלך."
            binding.btnGrantPermission.visibility = View.GONE
        } else {
            binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.warning)
            binding.tvStatusIcon.text = "⚠️"
            binding.tvStatusTitle.text = "נדרשת הרשאת גישה להתראות"
            binding.tvStatusDesc.text = "כדי שהאפליקציה תוכל לזהות תשלומים מ-Google Wallet, יש לאשר גישה להאזנה להתראות."
            binding.btnGrantPermission.visibility = View.VISIBLE
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(pkgName) == true
    }

    private fun setupWebhookSettings() {
        val currentUrl = Prefs.getWebhookUrl(this)
        binding.etWebhookUrl.setText(currentUrl)

        binding.btnSaveUrl.setOnClickListener {
            val newUrl = binding.etWebhookUrl.text?.toString()?.trim() ?: ""
            if (newUrl.isNotBlank() && (newUrl.startsWith("http://") || newUrl.startsWith("https://"))) {
                Prefs.setWebhookUrl(this, newUrl)
                Toast.makeText(this, "הכתובת נשמרה בהצלחה", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "נא להזין כתובת URL תקינה", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResetUrl.setOnClickListener {
            Prefs.resetWebhookUrl(this)
            binding.etWebhookUrl.setText(Prefs.getWebhookUrl(this))
            Toast.makeText(this, "הכתובת אופסה לברירת המחדל", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTestButton() {
        binding.btnSendTest.setOnClickListener {
            val webhookUrl = Prefs.getWebhookUrl(this)
            binding.btnSendTest.isEnabled = false
            binding.tvTestResult.visibility = View.VISIBLE
            binding.tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvTestResult.text = "שולח בקשת בדיקה ל-Vercel..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonPayload = JSONObject().apply {
                        put("text", "שילמת 20.00 ₪ בדיקת חיבור מאפליקציית אנדרואיד")
                        put("source", "android_test_button")
                    }

                    val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url(webhookUrl)
                        .post(requestBody)
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""

                    withContext(Dispatchers.Main) {
                        binding.btnSendTest.isEnabled = true
                        if (response.isSuccessful) {
                            binding.tvTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                            binding.tvTestResult.text = "✅ עסקת בדיקה (20 ₪) נקלטה בהצלחה בשרת (200 OK)!"
                            Prefs.addLog(this@MainActivity, "✅ בדיקה הצליחה: 20 ₪ (200 OK)")
                        } else {
                            binding.tvTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warning))
                            binding.tvTestResult.text = "⚠️ השרת החזיר שגיאה: ${response.code}\n$responseBody"
                            Prefs.addLog(this@MainActivity, "⚠️ שגיאת בדיקה: ${response.code}")
                        }
                        refreshLogs()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnSendTest.isEnabled = true
                        binding.tvTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warning))
                        binding.tvTestResult.text = "❌ נכשל בשליחה: ${e.message}"
                        Prefs.addLog(this@MainActivity, "❌ נכשל בחיבור: ${e.message}")
                        refreshLogs()
                    }
                }
            }
        }
    }

    private fun setupLogs() {
        refreshLogs()
        binding.btnClearLogs.setOnClickListener {
            Prefs.clearLogs(this)
            refreshLogs()
        }
    }

    private fun refreshLogs() {
        val logs = Prefs.getLogs(this)
        if (logs.isBlank()) {
            binding.tvLogs.text = "ממתין לתשלום ראשון..."
        } else {
            binding.tvLogs.text = logs
        }
    }
}
