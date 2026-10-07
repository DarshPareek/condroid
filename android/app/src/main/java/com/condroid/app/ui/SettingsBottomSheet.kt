package com.condroid.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsBottomSheet(
    private val context: Context,
    private val onConnectSettingsChanged: ((host: String, port: Int, rateHz: Int) -> Unit)? = null,
    private val onHapticToggled: ((Boolean) -> Unit)? = null,
    private val onGyroToggled: ((Boolean) -> Unit)? = null,
    private val onHudToggled: ((Boolean) -> Unit)? = null
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("condroid_prefs", Context.MODE_PRIVATE)

    fun show() {
        val lastHost = prefs.getString("last_host", "127.0.0.1") ?: "127.0.0.1"
        val lastPort = prefs.getInt("last_port", 8448)
        val lastRate = prefs.getInt("last_rate", 120)
        val hapticsEnabled = prefs.getBoolean("pref_haptics", true)
        val gyroEnabled = prefs.getBoolean("pref_gyro", false)
        val hudEnabled = prefs.getBoolean("pref_hud", true)

        val dynamicPrimary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#00E5FF"))
        val dynamicSurfaceContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainer, Color.parseColor("#141922"))

        val dialog = BottomSheetDialog(context)

        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 40, 56, 40)
            setBackgroundColor(dynamicSurfaceContainer)
        }
        scroll.addView(container)

        // Title
        val titleView = TextView(context).apply {
            text = "Condroid Settings ⚙"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        container.addView(titleView)

        // --- Connection Section ---
        val sectionConn = TextView(context).apply {
            text = "HOST CONNECTION"
            textSize = 12f
            setTextColor(dynamicPrimary)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 12)
        }
        container.addView(sectionConn)

        // Host IP
        val ipLayout = TextInputLayout(context).apply {
            hint = "Host IP Address"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(16f, 16f, 16f, 16f)
        }
        val ipEdit = TextInputEditText(ipLayout.context).apply {
            setText(lastHost)
        }
        ipLayout.addView(ipEdit)
        container.addView(ipLayout)

        // Quick Preset Chips
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
            weightSum = 3f
        }
        fun makePresetBtn(title: String, ip: String) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = title
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            cornerRadius = 14
            setOnClickListener { ipEdit.setText(ip) }
        }
        presetRow.addView(makePresetBtn("ADB USB", "127.0.0.1"))
        presetRow.addView(makePresetBtn("Hotspot", "10.42.0.1"))
        presetRow.addView(makePresetBtn("LAN", "192.168.1.2"))
        container.addView(presetRow)

        // Port
        val portLayout = TextInputLayout(context).apply {
            hint = "Host Port (UDP / TCP)"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(16f, 16f, 16f, 16f)
        }
        val portEdit = TextInputEditText(portLayout.context).apply {
            setText(lastPort.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        portLayout.addView(portEdit)
        container.addView(portLayout)

        // Polling Rate
        val rateLabel = TextView(context).apply {
            text = "Streaming Polling Rate"
            setTextColor(Color.parseColor("#89929B"))
            textSize = 13f
            setPadding(0, 16, 0, 8)
        }
        container.addView(rateLabel)

        val rateSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("60 Hz (Standard)", "120 Hz (Recommended - Smooth)", "240 Hz (Ultra Low Latency)")
            )
            setSelection(if (lastRate == 240) 2 else if (lastRate == 120) 1 else 0)
        }
        container.addView(rateSpinner)

        // --- Controls & Feedback Section ---
        val sectionPref = TextView(context).apply {
            text = "CONTROLS & FEEDBACK"
            textSize = 12f
            setTextColor(dynamicPrimary)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 12)
        }
        container.addView(sectionPref)

        // Haptic feedback switch
        val hapticSwitch = MaterialSwitch(context).apply {
            text = "Vibration Feedback"
            textSize = 14f
            isChecked = hapticsEnabled
            setPadding(0, 8, 0, 8)
        }
        container.addView(hapticSwitch)

        // Gyro switch
        val gyroSwitch = MaterialSwitch(context).apply {
            text = "Enable Gyroscope Aiming"
            textSize = 14f
            isChecked = gyroEnabled
            setPadding(0, 8, 0, 8)
        }
        container.addView(gyroSwitch)

        // HUD switch
        val hudSwitch = MaterialSwitch(context).apply {
            text = "Display Telemetry HUD (RTT & FPS)"
            textSize = 14f
            isChecked = hudEnabled
            setPadding(0, 8, 0, 8)
        }
        container.addView(hudSwitch)

        // Apply & Save Button
        val saveBtn = MaterialButton(context).apply {
            text = "Save & Apply Settings"
            textSize = 14f
            cornerRadius = 16
            setPadding(0, 24, 0, 24)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 32
            }
        }
        container.addView(saveBtn)

        saveBtn.setOnClickListener {
            val host = ipEdit.text.toString().trim()
            val port = portEdit.text.toString().toIntOrNull() ?: 8448
            val rate = when (rateSpinner.selectedItemPosition) {
                2 -> 240
                1 -> 120
                else -> 60
            }

            prefs.edit()
                .putString("last_host", host)
                .putInt("last_port", port)
                .putInt("last_rate", rate)
                .putBoolean("pref_haptics", hapticSwitch.isChecked)
                .putBoolean("pref_gyro", gyroSwitch.isChecked)
                .putBoolean("pref_hud", hudSwitch.isChecked)
                .apply()

            onConnectSettingsChanged?.invoke(host, port, rate)
            onHapticToggled?.invoke(hapticSwitch.isChecked)
            onGyroToggled?.invoke(gyroSwitch.isChecked)
            onHudToggled?.invoke(hudSwitch.isChecked)

            Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setContentView(scroll)
        dialog.show()
    }
}
