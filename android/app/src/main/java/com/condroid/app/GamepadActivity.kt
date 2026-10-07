package com.condroid.app

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.condroid.app.haptics.HapticEngine
import com.condroid.app.network.UdpSender
import com.condroid.app.sensors.GyroTracker
import com.condroid.app.ui.GamepadView
import com.condroid.app.ui.SettingsBottomSheet
import com.google.android.material.color.DynamicColors

class GamepadActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRESET_ID = "extra_preset_id"
        const val EXTRA_EDIT_MODE = "extra_edit_mode"
    }

    private lateinit var gamepadView: GamepadView
    private lateinit var hapticEngine: HapticEngine
    private lateinit var gyroTracker: GyroTracker
    private lateinit var udpSender: UdpSender

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hapticEngine = HapticEngine(this)
        gyroTracker = GyroTracker(this)

        val prefs = getSharedPreferences("condroid_prefs", Context.MODE_PRIVATE)
        val hudPref = prefs.getBoolean("pref_hud", true)
        val gyroPref = prefs.getBoolean("pref_gyro", false)
        gyroTracker.enabled = gyroPref

        udpSender = UdpSender(hapticEngine) { rttMs, rateHz ->
            runOnUiThread {
                if (rttMs >= 0) gamepadView.telemetryPingMs = rttMs
                if (rateHz >= 0) gamepadView.telemetryRateHz = rateHz
                gamepadView.invalidate()
            }
        }.apply {
            onSlotAssigned = { slot, _ ->
                runOnUiThread {
                    gamepadView.assignedPlayerSlot = slot
                    gamepadView.invalidate()
                }
            }
        }

        gamepadView = GamepadView(this).apply {
            udpSender = this@GamepadActivity.udpSender
            hapticEngine = this@GamepadActivity.hapticEngine
            showHud = hudPref
            isGyroActive = gyroPref

            val presetId = intent.getStringExtra(EXTRA_PRESET_ID)
            if (presetId != null) {
                val p = layoutManager.getPreset(presetId)
                if (p != null) {
                    currentPreset = p
                }
            }

            onHomeClicked = {
                finish()
            }

            onSettingsClicked = {
                SettingsBottomSheet(
                    context = this@GamepadActivity,
                    onConnectSettingsChanged = { host, port, rateHz ->
                        udpSender?.connect(host, port, rateHz)
                    },
                    onHapticToggled = { enabled ->
                        // Haptic engine respects prefs internally or when performing clicks
                    },
                    onGyroToggled = { enabled ->
                        gyroTracker.enabled = enabled
                        isGyroActive = enabled
                        invalidate()
                    },
                    onHudToggled = { enabled ->
                        showHud = enabled
                        invalidate()
                    }
                ).show()
            }

            onGyroToggleClicked = {
                gyroTracker.enabled = !gyroTracker.enabled
                isGyroActive = gyroTracker.enabled
                prefs.edit().putBoolean("pref_gyro", gyroTracker.enabled).apply()
                invalidate()
                val status = if (gyroTracker.enabled) "enabled" else "disabled"
                Toast.makeText(this@GamepadActivity, "Gyro Aiming $status", Toast.LENGTH_SHORT).show()
            }

            onEditModeChanged = { inEditMode ->
                if (!inEditMode) {
                    Toast.makeText(this@GamepadActivity, "Layout saved!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setContentView(gamepadView)
        hideSystemUI()

        // Set initial edit mode if requested
        val startInEditMode = intent.getBooleanExtra(EXTRA_EDIT_MODE, false)
        if (startInEditMode) {
            gamepadView.isEditMode = true
            Toast.makeText(this, "Select any button to change shape, size, or drag to reposition", Toast.LENGTH_LONG).show()
        }

        // Handle Back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (gamepadView.isEditMode) {
                    gamepadView.isEditMode = false
                } else {
                    finish()
                }
            }
        })

        // Connect to last host
        val lastHost = prefs.getString("last_host", "127.0.0.1") ?: "127.0.0.1"
        val lastPort = prefs.getInt("last_port", 8448)
        val lastRate = prefs.getInt("last_rate", 120)
        udpSender.connect(lastHost, lastPort, lastRate)

        // Periodic gyro updates
        Thread {
            while (!isFinishing) {
                if (gyroTracker.enabled && !gamepadView.isEditMode) {
                    udpSender.updateState {
                        it.gyroX = gyroTracker.gyroX
                        it.gyroY = gyroTracker.gyroY
                        it.gyroZ = gyroTracker.gyroZ
                    }
                }
                try { Thread.sleep(8) } catch (_: InterruptedException) { break }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gyroTracker.enabled = false
        udpSender.disconnect()
    }

    private fun hideSystemUI() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Exception) {}
    }
}
