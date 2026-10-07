package com.condroid.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class CondroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Applies Material You (Monet dynamic colors) extracted from the phone's wallpaper / system accent
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
