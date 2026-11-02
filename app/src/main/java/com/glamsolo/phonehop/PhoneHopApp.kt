package com.glamsolo.phonehop

import android.app.Application
import android.content.Context

class PhoneHopApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** The app-wide object graph (small enough not to need a DI framework). */
val Context.graph: AppGraph
    get() = (applicationContext as PhoneHopApp).graph
