package app.gstreminder.data

import android.content.Context
import android.content.SharedPreferences
import app.gstreminder.core.MessageBuilder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var dailyLimit: Int
        get() = prefs.getInt("dailyLimit", 100)
        set(v) = prefs.edit().putInt("dailyLimit", v.coerceAtLeast(1)).apply()

    var template: String
        get() = prefs.getString("template", null) ?: MessageBuilder.DEFAULT_TEMPLATE
        set(v) = prefs.edit().putString("template", v).apply()

    var sender: String
        get() = prefs.getString("sender", null) ?: MessageBuilder.DEFAULT_SENDER
        set(v) = prefs.edit().putString("sender", v).apply()

    var autoSend: Boolean
        get() = prefs.getBoolean("autoSend", true)
        set(v) = prefs.edit().putBoolean("autoSend", v).apply()

    /** Minutes after midnight for the daily automatic batch. */
    var sendAtMinutes: Int
        get() = prefs.getInt("sendAt", 10 * 60)
        set(v) = prefs.edit().putInt("sendAt", v).apply()

    /** Automatic sending only between these hours (manual sending ignores them). */
    var startHour: Int
        get() = prefs.getInt("startHour", 9)
        set(v) = prefs.edit().putInt("startHour", v).apply()

    var endHour: Int
        get() = prefs.getInt("endHour", 20)
        set(v) = prefs.edit().putInt("endHour", v).apply()

    var gapSeconds: Int
        get() = prefs.getInt("gapSeconds", 4)
        set(v) = prefs.edit().putInt("gapSeconds", v.coerceIn(1, 60)).apply()

    /** -1 = phone's default SIM for SMS. */
    var subscriptionId: Int
        get() = prefs.getInt("subId", -1)
        set(v) = prefs.edit().putInt("subId", v).apply()

    var subscriptionLabel: String
        get() = prefs.getString("subLabel", null) ?: "Default SIM"
        set(v) = prefs.edit().putString("subLabel", v).apply()

    /** Human readable result of the last batch, shown on the dashboard. */
    var lastRun: String
        get() = prefs.getString("lastRun", null) ?: ""
        set(v) = prefs.edit().putString("lastRun", v).apply()

    /** Emits whenever any setting changes (and once immediately). */
    val changes: Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        trySend(Unit)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
