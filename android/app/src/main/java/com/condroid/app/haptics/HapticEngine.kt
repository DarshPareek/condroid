package com.condroid.app.haptics

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticEngine(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val prefs = context.getSharedPreferences("condroid_prefs", Context.MODE_PRIVATE)

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean("pref_haptics", true)
        set(value) {
            prefs.edit().putBoolean("pref_haptics", value).apply()
        }

    /**
     * Crisp tactile click feedback for on-screen button touch
     */
    fun performClick() {
        if (!hapticFeedbackEnabled || vibrator == null || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            vibrator.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(15, 100)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(15)
        }
    }

    /**
     * Controller rumble vibration triggered from PC host
     */
    fun rumble(weakMagnitude: Int, strongMagnitude: Int, durationMs: Int) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        val amplitude = ((weakMagnitude * 0.4 + strongMagnitude * 0.6).toInt()).coerceIn(1, 255)
        val duration = durationMs.toLong().coerceIn(20L, 1000L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(duration, amplitude)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}
