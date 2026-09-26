package com.magnate.compass

import android.app.Application
import com.magnate.compass.di.AppContainer

class MagnateApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
