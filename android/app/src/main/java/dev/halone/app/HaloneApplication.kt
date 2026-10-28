package dev.halone.app

import android.app.Application

class HaloneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // GeckoView's child processes create the Application too, and each
        // would start the log afresh over the main process's run.
        if (getProcessName() == packageName) CrashReports.install(this)
    }
}
