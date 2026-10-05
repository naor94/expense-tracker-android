package com.naor.expensetracker

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private const val PREFS_NAME = "expense_tracker_prefs"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_LOGS = "recent_logs"
    private const val DEFAULT_URL = "https://expense-tracker-web-sigma-seven.vercel.app/api/webhook/google-pay"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getWebhookUrl(context: Context): String {
        return getPrefs(context).getString(KEY_WEBHOOK_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun setWebhookUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_WEBHOOK_URL, url.trim()).apply()
    }

    fun resetWebhookUrl(context: Context) {
        getPrefs(context).edit().putString(KEY_WEBHOOK_URL, DEFAULT_URL).apply()
    }

    fun addLog(context: Context, logEntry: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formatted = "[$time] $logEntry"
        val existing = getLogs(context)
        val lines = existing.split("\n").filter { it.isNotBlank() }
        val updated = (listOf(formatted) + lines).take(15).joinToString("\n")
        getPrefs(context).edit().putString(KEY_LOGS, updated).apply()
    }

    fun getLogs(context: Context): String {
        return getPrefs(context).getString(KEY_LOGS, "") ?: ""
    }

    fun clearLogs(context: Context) {
        getPrefs(context).edit().remove(KEY_LOGS).apply()
    }
}
