package com.condroid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.condroid.app.model.LayoutPreset
import com.condroid.app.ui.LayoutManager
import com.condroid.app.ui.LayoutPresetAdapter
import com.condroid.app.ui.SettingsBottomSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import com.google.android.material.color.DynamicColors

class HomeActivity : AppCompatActivity() {

    private lateinit var layoutManager: LayoutManager
    private lateinit var adapter: LayoutPresetAdapter
    private lateinit var txtHostPill: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        layoutManager = LayoutManager(this)
        txtHostPill = findViewById(R.id.txtHostPill)

        val recycler = findViewById<RecyclerView>(R.id.recyclerPresets)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = LayoutPresetAdapter(
            presets = layoutManager.getAllPresets(),
            activePresetId = layoutManager.activePresetId,
            onPlay = { preset ->
                layoutManager.setActivePreset(preset.id)
                launchGamepad(preset.id, editMode = false)
            },
            onCustomize = { preset ->
                layoutManager.setActivePreset(preset.id)
                launchGamepad(preset.id, editMode = true)
            },
            onClone = { preset ->
                promptClonePreset(preset)
            },
            onDelete = { preset ->
                confirmDeletePreset(preset)
            }
        )
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            openSettings()
        }

        findViewById<MaterialButton>(R.id.btnNewLayout).setOnClickListener {
            promptCreateNewLayout()
        }

        updateHostPill()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        updateHostPill()
    }

    private fun refreshList() {
        adapter.updateData(layoutManager.getAllPresets(), layoutManager.activePresetId)
    }

    private fun updateHostPill() {
        val prefs = getSharedPreferences("condroid_prefs", Context.MODE_PRIVATE)
        val host = prefs.getString("last_host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("last_port", 8448)
        txtHostPill.text = "● $host:$port"
    }

    private fun launchGamepad(presetId: String, editMode: Boolean) {
        val intent = Intent(this, GamepadActivity::class.java).apply {
            putExtra(GamepadActivity.EXTRA_PRESET_ID, presetId)
            putExtra(GamepadActivity.EXTRA_EDIT_MODE, editMode)
        }
        startActivity(intent)
    }

    private fun openSettings() {
        SettingsBottomSheet(
            context = this,
            onConnectSettingsChanged = { host, port, _ ->
                updateHostPill()
            }
        ).show()
    }

    private fun promptClonePreset(preset: LayoutPreset) {
        val input = EditText(this).apply {
            setText("${preset.name} (Copy)")
            selectAll()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Clone Layout Preset")
            .setMessage("Enter a name for the cloned layout:")
            .setView(input)
            .setPositiveButton("Clone") { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { "${preset.name} Copy" }
                val cloned = layoutManager.duplicatePreset(preset.id, newName)
                refreshList()
                Toast.makeText(this, "Created layout '${cloned.name}'", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCreateNewLayout() {
        val input = EditText(this).apply {
            hint = "My Custom Layout"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Create New Layout")
            .setMessage("Base your new layout on the active preset:")
            .setView(input)
            .setPositiveButton("Create & Edit") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Custom Layout ${System.currentTimeMillis() % 1000}" }
                val newPreset = layoutManager.duplicatePreset(layoutManager.activePresetId, name)
                refreshList()
                launchGamepad(newPreset.id, editMode = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePreset(preset: LayoutPreset) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Layout")
            .setMessage("Are you sure you want to delete '${preset.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                if (layoutManager.deletePreset(preset.id)) {
                    refreshList()
                    Toast.makeText(this, "Deleted '${preset.name}'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
