package com.smartlife798.app.core

/** Only fixed public release URLs are sent to the proxy, never business traffic. */
enum class UpdateSource(val label: String) {
    GITHUB("GitHub官方"), GH_PROXY("gh-proxy(国内加速)");

    fun url(original: String): String {
        require(original.startsWith("${UpdatePolicy.REPOSITORY}/releases/"))
        return if (this == GITHUB) original else "https://gh-proxy.org/$original"
    }

    fun attempts(): List<UpdateSource> = listOf(this) + entries.filter { it != this }

    companion object {
        fun fromName(value: String?) = entries.firstOrNull { it.name == value } ?: GITHUB
    }
}
