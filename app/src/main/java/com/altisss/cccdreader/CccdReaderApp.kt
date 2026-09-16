package com.altisss.cccdreader

import android.app.Application
import com.altisss.cccdreader.crash.GlobalCrashHandler

class CccdReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalCrashHandler.install(this)
    }
}