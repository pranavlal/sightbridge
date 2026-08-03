package com.sightbridge.app

import android.app.Application
import com.meta.wearable.dat.core.Wearables

class SightBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Meta Wearables Device Access Toolkit (DAT) SDK once per process at app startup
        Wearables.initialize(this)
    }
}
