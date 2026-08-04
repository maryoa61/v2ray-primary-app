package com.example

import android.app.Application

class V2RayDanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
