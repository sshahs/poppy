package app.poppy.core

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Small, synchronous preferences. Everything sensitive lives in [SessionStore]. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("poppy_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(ThemeMode.entries[prefs.getInt(KEY_THEME, 0)])
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    private val _amoled = MutableStateFlow(prefs.getBoolean(KEY_AMOLED, false))
    val amoled: StateFlow<Boolean> = _amoled

    private val _sendTyping = MutableStateFlow(prefs.getBoolean(KEY_TYPING, true))
    val sendTyping: StateFlow<Boolean> = _sendTyping

    private val _sendReadReceipts = MutableStateFlow(prefs.getBoolean(KEY_RECEIPTS, true))
    val sendReadReceipts: StateFlow<Boolean> = _sendReadReceipts

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putInt(KEY_THEME, mode.ordinal) }
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC, enabled) }
        _dynamicColor.value = enabled
    }

    fun setAmoled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AMOLED, enabled) }
        _amoled.value = enabled
    }

    fun setSendTyping(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_TYPING, enabled) }
        _sendTyping.value = enabled
    }

    fun setSendReadReceipts(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_RECEIPTS, enabled) }
        _sendReadReceipts.value = enabled
    }

    var pollingEnabled: Boolean
        get() = prefs.getBoolean(KEY_POLLING, false)
        set(value) = prefs.edit { putBoolean(KEY_POLLING, value) }

    var pushEndpoint: String?
        get() = prefs.getString(KEY_ENDPOINT, null)
        set(value) = prefs.edit { putString(KEY_ENDPOINT, value) }

    var pushGateway: String?
        get() = prefs.getString(KEY_GATEWAY, null)
        set(value) = prefs.edit { putString(KEY_GATEWAY, value) }

    var lastHomeserver: String
        get() = prefs.getString(KEY_LAST_HS, "matrix.org") ?: "matrix.org"
        set(value) = prefs.edit { putString(KEY_LAST_HS, value) }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_AMOLED = "amoled"
        const val KEY_TYPING = "send_typing"
        const val KEY_RECEIPTS = "send_receipts"
        const val KEY_POLLING = "polling"
        const val KEY_ENDPOINT = "push_endpoint"
        const val KEY_GATEWAY = "push_gateway"
        const val KEY_LAST_HS = "last_hs"
    }
}
