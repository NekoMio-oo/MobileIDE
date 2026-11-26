package dev.mobileide

import android.app.Application
import dev.mobileide.di.AppContainer

class MobileIdeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
