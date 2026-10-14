package com.sightbridge.app

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class SightBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            Wearables.initialize(this).onFailure { error, cause ->
                Log.w("SightBridgeApp", "Meta Wearables DAT initialization failed: ${error.description}", cause)
            }
        }.onFailure { error ->
            Log.w("SightBridgeApp", "Meta Wearables DAT initialization unavailable", error)
        }
    }
}
