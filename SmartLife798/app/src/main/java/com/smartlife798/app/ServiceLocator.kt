package com.smartlife798.app

import android.content.Context
import com.smartlife798.app.core.Appearance
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session

object ServiceLocator {
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        Store.init(context)
        com.smartlife798.app.core.OriginalAppVersion.restore(Store.originalAppVersion)
        session.restore()
        Appearance.restore()
    }
}
