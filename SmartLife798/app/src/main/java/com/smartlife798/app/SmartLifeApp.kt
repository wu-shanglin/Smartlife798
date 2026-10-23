package com.smartlife798.app

import android.app.Application

class SmartLifeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
