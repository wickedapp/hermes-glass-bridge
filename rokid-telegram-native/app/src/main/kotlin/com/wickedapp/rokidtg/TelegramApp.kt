package com.wickedapp.rokidtg

import android.app.Application
import timber.log.Timber

class TelegramApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
