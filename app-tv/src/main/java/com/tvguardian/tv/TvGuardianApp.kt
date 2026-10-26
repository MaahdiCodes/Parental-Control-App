package com.tvguardian.tv

import android.app.Application

class TvGuardianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpikeState.init(this)
        SpikeState.log("App started")
        TvLock.init(this)
        Agent.start(this)
    }
}
