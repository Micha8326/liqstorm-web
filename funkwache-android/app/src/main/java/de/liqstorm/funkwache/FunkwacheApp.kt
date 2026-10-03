package de.liqstorm.funkwache

import android.app.Application
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Notifier

class FunkwacheApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        Notifier.createChannels(this)
    }
}
