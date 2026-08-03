package com.sightbridge.app

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class SightBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            Wearables.initialize(this)
            Log.i("SightBridgeApp", "Meta Wearables DAT SDK initialized successfully")
        }.onFailure { e ->
            Log.w("SightBridgeApp", "Meta Wearables DAT SDK initialization deferred or unavailable: ${e.message}")
        }
    }
}
