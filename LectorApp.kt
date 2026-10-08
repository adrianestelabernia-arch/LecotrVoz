package com.lectorvoz.app

import android.app.Application

class LectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReaderEngine.init(this)
    }
}
