package com.smartlife798.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app picks light/dark. Persisted per install, not per account. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Install-wide appearance preferences.
 *
 * Kept out of [com.smartlife798.app.vm.MainViewModel] because they survive logout
 * and are read by the theme wrapper before any screen exists.
 */
object Appearance {
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(false)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    /** Called once from [com.smartlife798.app.ServiceLocator] after [Store.init]. */
    fun restore() {
        _mode.value = ThemeMode.fromName(Store.themeModeName())
        _dynamicColor.value = Store.dynamicColor()
    }

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        Store.saveThemeMode(mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        Store.saveDynamicColor(enabled)
    }
}
