package com.mrjackspade.kairodos

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import android.view.WindowInsets

/** Private debug measurement window: actual panel cadence, no library or guest data. */
class VideoPresentationActivity : Activity() {
    lateinit var video: SurfaceView
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = SurfaceView(this)
        setContentView(video)
        window.decorView.post { window.insetsController?.hide(WindowInsets.Type.systemBars()) }
    }
}
