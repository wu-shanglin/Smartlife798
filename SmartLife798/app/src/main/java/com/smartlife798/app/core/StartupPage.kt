package com.smartlife798.app.core

/** Installation preference; unknown values retain the historical home default. */
enum class StartupPage(val route: String, val label: String) {
    HOME("home", "首页"),
    DEVICES("devices", "设备页");

    companion object {
        fun fromName(name: String?): StartupPage = entries.firstOrNull { it.name == name } ?: HOME
    }
}
