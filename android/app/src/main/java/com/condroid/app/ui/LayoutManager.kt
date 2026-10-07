package com.condroid.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.condroid.app.model.ElementConfig
import com.condroid.app.model.ElementShape
import com.condroid.app.model.ElementType
import com.condroid.app.model.LayoutPreset
import com.condroid.app.protocol.Buttons
import org.json.JSONArray
import org.json.JSONObject

class LayoutManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("condroid_layout_prefs_v2", Context.MODE_PRIVATE)

    companion object {
        const val PRESET_XBOX = "preset_xbox"
        const val PRESET_FIGHTING = "preset_fighting"
        const val PRESET_RACING = "preset_racing"
        const val PRESET_SHOOTER = "preset_shooter"

        // Element ID Constants
        const val ID_STICK_LEFT = "stick_left"
        const val ID_STICK_RIGHT = "stick_right"

        const val ID_DPAD_UP = "dpad_up"
        const val ID_DPAD_DOWN = "dpad_down"
        const val ID_DPAD_LEFT = "dpad_left"
        const val ID_DPAD_RIGHT = "dpad_right"

        const val ID_BTN_A = "btn_a"
        const val ID_BTN_B = "btn_b"
        const val ID_BTN_X = "btn_x"
        const val ID_BTN_Y = "btn_y"

        const val ID_BTN_LB = "btn_lb"
        const val ID_BTN_LT = "btn_lt"
        const val ID_BTN_RB = "btn_rb"
        const val ID_BTN_RT = "btn_rt"

        const val ID_BTN_SELECT = "btn_select"
        const val ID_BTN_START = "btn_start"
        const val ID_BTN_GUIDE = "btn_guide"
    }

    private val presets = mutableListOf<LayoutPreset>()
    var activePresetId: String = PRESET_XBOX
        private set

    init {
        loadAll()
    }

    fun getAllPresets(): List<LayoutPreset> = presets.toList()

    fun getActivePreset(): LayoutPreset {
        return presets.firstOrNull { it.id == activePresetId }
            ?: presets.firstOrNull()
            ?: createStandardXboxPreset()
    }

    fun setActivePreset(presetId: String) {
        if (presets.any { it.id == presetId }) {
            activePresetId = presetId
            prefs.edit().putString("active_preset_id", presetId).apply()
        }
    }

    fun getPreset(presetId: String): LayoutPreset? {
        return presets.firstOrNull { it.id == presetId }
    }

    fun savePreset(preset: LayoutPreset) {
        val idx = presets.indexOfFirst { it.id == preset.id }
        if (idx != -1) {
            presets[idx] = preset
        } else {
            presets.add(preset)
        }
        persistPresets()
    }

    fun duplicatePreset(sourceId: String, newName: String): LayoutPreset {
        val source = getPreset(sourceId) ?: getActivePreset()
        val newId = "custom_${System.currentTimeMillis()}"
        val copy = source.deepCopy(newId, newName)
        presets.add(copy)
        activePresetId = newId
        persistPresets()
        prefs.edit().putString("active_preset_id", newId).apply()
        return copy
    }

    fun deletePreset(presetId: String): Boolean {
        val preset = getPreset(presetId) ?: return false
        if (preset.isBuiltIn) return false // Built-ins cannot be deleted

        presets.removeAll { it.id == presetId }
        if (activePresetId == presetId) {
            activePresetId = PRESET_XBOX
            prefs.edit().putString("active_preset_id", PRESET_XBOX).apply()
        }
        persistPresets()
        return true
    }

    fun resetPresetToDefault(presetId: String) {
        when (presetId) {
            PRESET_XBOX -> savePreset(createStandardXboxPreset())
            PRESET_FIGHTING -> savePreset(createFightingPreset())
            PRESET_RACING -> savePreset(createRacingPreset())
            PRESET_SHOOTER -> savePreset(createShooterPreset())
        }
    }

    private fun loadAll() {
        presets.clear()

        // 1. Load built-ins
        val defaultXbox = createStandardXboxPreset()
        val defaultFighting = createFightingPreset()
        val defaultRacing = createRacingPreset()
        val defaultShooter = createShooterPreset()

        presets.add(defaultXbox)
        presets.add(defaultFighting)
        presets.add(defaultRacing)
        presets.add(defaultShooter)

        // 2. Load custom / modified presets from SharedPreferences
        val rawJson = prefs.getString("custom_presets_json", null)
        if (!rawJson.isNullOrEmpty()) {
            try {
                val array = JSONArray(rawJson)
                for (i in 0 until array.length()) {
                    val p = LayoutPreset.fromJson(array.getJSONObject(i))
                    val existingIdx = presets.indexOfFirst { it.id == p.id }
                    if (existingIdx != -1) {
                        presets[existingIdx] = p
                    } else {
                        presets.add(p)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        activePresetId = prefs.getString("active_preset_id", PRESET_XBOX) ?: PRESET_XBOX
        if (presets.none { it.id == activePresetId }) {
            activePresetId = PRESET_XBOX
        }
    }

    private fun persistPresets() {
        val array = JSONArray()
        for (p in presets) {
            array.put(p.toJson())
        }
        prefs.edit()
            .putString("custom_presets_json", array.toString())
            .putString("active_preset_id", activePresetId)
            .apply()
    }

    // --- Built-in Preset Factories ---

    fun createStandardXboxPreset(): LayoutPreset {
        val p = LayoutPreset(
            id = PRESET_XBOX,
            name = "Standard Xbox 360",
            description = "Classic console layout with asymmetric sticks and diamond ABXY",
            isBuiltIn = true
        )

        // Joysticks
        p.elements[ID_STICK_LEFT] = ElementConfig(ID_STICK_LEFT, "Left Stick", ElementType.STICK, 0.18f, 0.65f, 1.0f, ElementShape.CIRCLE, true, "LS", 0, Color.parseColor("#00E5FF"))
        p.elements[ID_STICK_RIGHT] = ElementConfig(ID_STICK_RIGHT, "Right Stick", ElementType.STICK, 0.68f, 0.70f, 1.0f, ElementShape.CIRCLE, true, "RS", 0, Color.parseColor("#00E5FF"))

        // Face Buttons (Diamond)
        p.elements[ID_BTN_Y] = ElementConfig(ID_BTN_Y, "Y Button", ElementType.BUTTON, 0.88f, 0.33f, 1.0f, ElementShape.CIRCLE, true, "Y", Buttons.BTN_Y, Color.parseColor("#FFD600"))
        p.elements[ID_BTN_B] = ElementConfig(ID_BTN_B, "B Button", ElementType.BUTTON, 0.94f, 0.45f, 1.0f, ElementShape.CIRCLE, true, "B", Buttons.BTN_B, Color.parseColor("#FF1744"))
        p.elements[ID_BTN_A] = ElementConfig(ID_BTN_A, "A Button", ElementType.BUTTON, 0.88f, 0.57f, 1.0f, ElementShape.CIRCLE, true, "A", Buttons.BTN_A, Color.parseColor("#00E676"))
        p.elements[ID_BTN_X] = ElementConfig(ID_BTN_X, "X Button", ElementType.BUTTON, 0.82f, 0.45f, 1.0f, ElementShape.CIRCLE, true, "X", Buttons.BTN_X, Color.parseColor("#2979FF"))

        // D-Pad Individual Buttons
        p.elements[ID_DPAD_UP] = ElementConfig(ID_DPAD_UP, "D-Pad Up", ElementType.BUTTON, 0.35f, 0.28f, 1.0f, ElementShape.TRIANGLE_UP, true, "▲", Buttons.DPAD_UP, Color.WHITE)
        p.elements[ID_DPAD_DOWN] = ElementConfig(ID_DPAD_DOWN, "D-Pad Down", ElementType.BUTTON, 0.35f, 0.48f, 1.0f, ElementShape.TRIANGLE_DOWN, true, "▼", Buttons.DPAD_DOWN, Color.WHITE)
        p.elements[ID_DPAD_LEFT] = ElementConfig(ID_DPAD_LEFT, "D-Pad Left", ElementType.BUTTON, 0.29f, 0.38f, 1.0f, ElementShape.TRIANGLE_LEFT, true, "◀", Buttons.DPAD_LEFT, Color.WHITE)
        p.elements[ID_DPAD_RIGHT] = ElementConfig(ID_DPAD_RIGHT, "D-Pad Right", ElementType.BUTTON, 0.41f, 0.38f, 1.0f, ElementShape.TRIANGLE_RIGHT, true, "▶", Buttons.DPAD_RIGHT, Color.WHITE)

        // Bumpers & Triggers
        p.elements[ID_BTN_LB] = ElementConfig(ID_BTN_LB, "LB Bumper", ElementType.BUTTON, 0.08f, 0.12f, 1.0f, ElementShape.ROUNDED_RECT, true, "LB", Buttons.BTN_LB, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_LT] = ElementConfig(ID_BTN_LT, "LT Trigger", ElementType.TRIGGER, 0.22f, 0.12f, 1.0f, ElementShape.ROUNDED_RECT, true, "LT", -1, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_RT] = ElementConfig(ID_BTN_RT, "RT Trigger", ElementType.TRIGGER, 0.78f, 0.12f, 1.0f, ElementShape.ROUNDED_RECT, true, "RT", -2, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_RB] = ElementConfig(ID_BTN_RB, "RB Bumper", ElementType.BUTTON, 0.92f, 0.12f, 1.0f, ElementShape.ROUNDED_RECT, true, "RB", Buttons.BTN_RB, Color.parseColor("#00E5FF"))

        // System Buttons
        p.elements[ID_BTN_SELECT] = ElementConfig(ID_BTN_SELECT, "View / Back", ElementType.BUTTON, 0.43f, 0.12f, 0.9f, ElementShape.ROUNDED_RECT, true, "VIEW", Buttons.BTN_SELECT, Color.LTGRAY)
        p.elements[ID_BTN_GUIDE] = ElementConfig(ID_BTN_GUIDE, "Guide / Home", ElementType.BUTTON, 0.50f, 0.12f, 1.0f, ElementShape.CIRCLE, true, "⊚", Buttons.BTN_GUIDE, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_START] = ElementConfig(ID_BTN_START, "Menu / Start", ElementType.BUTTON, 0.57f, 0.12f, 0.9f, ElementShape.ROUNDED_RECT, true, "MENU", Buttons.BTN_START, Color.LTGRAY)

        return p
    }

    fun createFightingPreset(): LayoutPreset {
        val p = LayoutPreset(
            id = PRESET_FIGHTING,
            name = "Arcade / Fighting",
            description = "Square & circle arcade buttons, enlarged D-Pad for combos",
            isBuiltIn = true
        )

        // Compact Sticks
        p.elements[ID_STICK_LEFT] = ElementConfig(ID_STICK_LEFT, "Left Stick", ElementType.STICK, 0.18f, 0.80f, 0.8f, ElementShape.CIRCLE, true, "LS", 0, Color.parseColor("#00E5FF"))
        p.elements[ID_STICK_RIGHT] = ElementConfig(ID_STICK_RIGHT, "Right Stick", ElementType.STICK, 0.88f, 0.80f, 0.8f, ElementShape.CIRCLE, false, "RS", 0, Color.parseColor("#00E5FF"))

        // Large Arcade D-Pad
        p.elements[ID_DPAD_UP] = ElementConfig(ID_DPAD_UP, "D-Pad Up", ElementType.BUTTON, 0.22f, 0.32f, 1.25f, ElementShape.TRIANGLE_UP, true, "▲", Buttons.DPAD_UP, Color.WHITE)
        p.elements[ID_DPAD_DOWN] = ElementConfig(ID_DPAD_DOWN, "D-Pad Down", ElementType.BUTTON, 0.22f, 0.56f, 1.25f, ElementShape.TRIANGLE_DOWN, true, "▼", Buttons.DPAD_DOWN, Color.WHITE)
        p.elements[ID_DPAD_LEFT] = ElementConfig(ID_DPAD_LEFT, "D-Pad Left", ElementType.BUTTON, 0.14f, 0.44f, 1.25f, ElementShape.TRIANGLE_LEFT, true, "◀", Buttons.DPAD_LEFT, Color.WHITE)
        p.elements[ID_DPAD_RIGHT] = ElementConfig(ID_DPAD_RIGHT, "D-Pad Right", ElementType.BUTTON, 0.30f, 0.44f, 1.25f, ElementShape.TRIANGLE_RIGHT, true, "▶", Buttons.DPAD_RIGHT, Color.WHITE)

        // 6-Button Arcade Layout: Row 1 (X, Y, RB) as Square, Row 2 (A, B, RT) as Circle
        p.elements[ID_BTN_X] = ElementConfig(ID_BTN_X, "X Button", ElementType.BUTTON, 0.68f, 0.38f, 1.15f, ElementShape.SQUARE, true, "X", Buttons.BTN_X, Color.parseColor("#2979FF"))
        p.elements[ID_BTN_Y] = ElementConfig(ID_BTN_Y, "Y Button", ElementType.BUTTON, 0.79f, 0.38f, 1.15f, ElementShape.SQUARE, true, "Y", Buttons.BTN_Y, Color.parseColor("#FFD600"))
        p.elements[ID_BTN_RB] = ElementConfig(ID_BTN_RB, "RB Bumper", ElementType.BUTTON, 0.90f, 0.38f, 1.15f, ElementShape.SQUARE, true, "RB", Buttons.BTN_RB, Color.parseColor("#00E5FF"))

        p.elements[ID_BTN_A] = ElementConfig(ID_BTN_A, "A Button", ElementType.BUTTON, 0.68f, 0.58f, 1.15f, ElementShape.CIRCLE, true, "A", Buttons.BTN_A, Color.parseColor("#00E676"))
        p.elements[ID_BTN_B] = ElementConfig(ID_BTN_B, "B Button", ElementType.BUTTON, 0.79f, 0.58f, 1.15f, ElementShape.CIRCLE, true, "B", Buttons.BTN_B, Color.parseColor("#FF1744"))
        p.elements[ID_BTN_RT] = ElementConfig(ID_BTN_RT, "RT Trigger", ElementType.TRIGGER, 0.90f, 0.58f, 1.15f, ElementShape.CIRCLE, true, "RT", -2, Color.parseColor("#00E5FF"))

        // Shoulders
        p.elements[ID_BTN_LB] = ElementConfig(ID_BTN_LB, "LB Bumper", ElementType.BUTTON, 0.08f, 0.12f, 1.0f, ElementShape.ROUNDED_RECT, true, "LB", Buttons.BTN_LB, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_LT] = ElementConfig(ID_BTN_LT, "LT Trigger", ElementType.TRIGGER, 0.22f, 0.12f, 1.0f, ElementShape.ROUNDED_RECT, true, "LT", -1, Color.parseColor("#00E5FF"))

        // System
        p.elements[ID_BTN_SELECT] = ElementConfig(ID_BTN_SELECT, "View / Back", ElementType.BUTTON, 0.44f, 0.12f, 0.9f, ElementShape.ROUNDED_RECT, true, "COIN", Buttons.BTN_SELECT, Color.LTGRAY)
        p.elements[ID_BTN_GUIDE] = ElementConfig(ID_BTN_GUIDE, "Guide / Home", ElementType.BUTTON, 0.50f, 0.12f, 1.0f, ElementShape.CIRCLE, true, "⊚", Buttons.BTN_GUIDE, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_START] = ElementConfig(ID_BTN_START, "Menu / Start", ElementType.BUTTON, 0.56f, 0.12f, 0.9f, ElementShape.ROUNDED_RECT, true, "START", Buttons.BTN_START, Color.LTGRAY)

        return p
    }

    fun createRacingPreset(): LayoutPreset {
        val p = LayoutPreset(
            id = PRESET_RACING,
            name = "Racing & Driving",
            description = "Enlarged analog triggers for precision throttle/braking",
            isBuiltIn = true
        )

        // Large Throttle / Brake Pedals
        p.elements[ID_BTN_LT] = ElementConfig(ID_BTN_LT, "Brake Pedal (LT)", ElementType.TRIGGER, 0.14f, 0.28f, 1.4f, ElementShape.ROUNDED_RECT, true, "BRAKE\nLT", -1, Color.parseColor("#FF1744"))
        p.elements[ID_BTN_RT] = ElementConfig(ID_BTN_RT, "Gas Pedal (RT)", ElementType.TRIGGER, 0.86f, 0.28f, 1.4f, ElementShape.ROUNDED_RECT, true, "GAS\nRT", -2, Color.parseColor("#00E676"))

        // Steering Stick
        p.elements[ID_STICK_LEFT] = ElementConfig(ID_STICK_LEFT, "Steering Stick", ElementType.STICK, 0.20f, 0.70f, 1.3f, ElementShape.CIRCLE, true, "STEER", 0, Color.parseColor("#00E5FF"))
        p.elements[ID_STICK_RIGHT] = ElementConfig(ID_STICK_RIGHT, "Look / Camera Stick", ElementType.STICK, 0.80f, 0.70f, 0.9f, ElementShape.CIRCLE, true, "CAM", 0, Color.parseColor("#00E5FF"))

        // Handbrake & Gear Shift Buttons
        p.elements[ID_BTN_A] = ElementConfig(ID_BTN_A, "Handbrake", ElementType.BUTTON, 0.92f, 0.55f, 1.2f, ElementShape.ROUNDED_RECT, true, "HB", Buttons.BTN_A, Color.parseColor("#FFD600"))
        p.elements[ID_BTN_X] = ElementConfig(ID_BTN_X, "Shift Down", ElementType.BUTTON, 0.78f, 0.55f, 1.0f, ElementShape.SQUARE, true, "▼", Buttons.BTN_X, Color.parseColor("#2979FF"))
        p.elements[ID_BTN_B] = ElementConfig(ID_BTN_B, "Shift Up", ElementType.BUTTON, 0.85f, 0.45f, 1.0f, ElementShape.SQUARE, true, "▲", Buttons.BTN_B, Color.parseColor("#2979FF"))
        p.elements[ID_BTN_Y] = ElementConfig(ID_BTN_Y, "Look Behind", ElementType.BUTTON, 0.85f, 0.65f, 1.0f, ElementShape.ROUNDED_RECT, true, "REV", Buttons.BTN_Y, Color.LTGRAY)

        // D-Pad for Menus
        p.elements[ID_DPAD_UP] = ElementConfig(ID_DPAD_UP, "D-Pad Up", ElementType.BUTTON, 0.38f, 0.60f, 0.8f, ElementShape.TRIANGLE_UP, true, "▲", Buttons.DPAD_UP, Color.WHITE)
        p.elements[ID_DPAD_DOWN] = ElementConfig(ID_DPAD_DOWN, "D-Pad Down", ElementType.BUTTON, 0.38f, 0.76f, 0.8f, ElementShape.TRIANGLE_DOWN, true, "▼", Buttons.DPAD_DOWN, Color.WHITE)
        p.elements[ID_DPAD_LEFT] = ElementConfig(ID_DPAD_LEFT, "D-Pad Left", ElementType.BUTTON, 0.33f, 0.68f, 0.8f, ElementShape.TRIANGLE_LEFT, true, "◀", Buttons.DPAD_LEFT, Color.WHITE)
        p.elements[ID_DPAD_RIGHT] = ElementConfig(ID_DPAD_RIGHT, "D-Pad Right", ElementType.BUTTON, 0.43f, 0.68f, 0.8f, ElementShape.TRIANGLE_RIGHT, true, "▶", Buttons.DPAD_RIGHT, Color.WHITE)

        // Bumpers & System
        p.elements[ID_BTN_LB] = ElementConfig(ID_BTN_LB, "LB Bumper", ElementType.BUTTON, 0.14f, 0.08f, 1.0f, ElementShape.ROUNDED_RECT, true, "LB", Buttons.BTN_LB, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_RB] = ElementConfig(ID_BTN_RB, "RB Bumper", ElementType.BUTTON, 0.86f, 0.08f, 1.0f, ElementShape.ROUNDED_RECT, true, "RB", Buttons.BTN_RB, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_SELECT] = ElementConfig(ID_BTN_SELECT, "View", ElementType.BUTTON, 0.44f, 0.10f, 0.9f, ElementShape.ROUNDED_RECT, true, "MAP", Buttons.BTN_SELECT, Color.LTGRAY)
        p.elements[ID_BTN_GUIDE] = ElementConfig(ID_BTN_GUIDE, "Guide", ElementType.BUTTON, 0.50f, 0.10f, 1.0f, ElementShape.CIRCLE, true, "⊚", Buttons.BTN_GUIDE, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_START] = ElementConfig(ID_BTN_START, "Pause", ElementType.BUTTON, 0.56f, 0.10f, 0.9f, ElementShape.ROUNDED_RECT, true, "PAUSE", Buttons.BTN_START, Color.LTGRAY)

        return p
    }

    fun createShooterPreset(): LayoutPreset {
        val p = LayoutPreset(
            id = PRESET_SHOOTER,
            name = "Shooter / FPS",
            description = "Optimized thumbsticks, large triggers & bumpers for aim & fire",
            isBuiltIn = true
        )

        // Sticks
        p.elements[ID_STICK_LEFT] = ElementConfig(ID_STICK_LEFT, "Movement Stick", ElementType.STICK, 0.18f, 0.65f, 1.1f, ElementShape.CIRCLE, true, "MOVE", 0, Color.parseColor("#00E5FF"))
        p.elements[ID_STICK_RIGHT] = ElementConfig(ID_STICK_RIGHT, "Aiming Stick", ElementType.STICK, 0.82f, 0.65f, 1.1f, ElementShape.CIRCLE, true, "AIM", 0, Color.parseColor("#00E5FF"))

        // Top Triggers & Bumpers
        p.elements[ID_BTN_LT] = ElementConfig(ID_BTN_LT, "Aim Down Sights (LT)", ElementType.TRIGGER, 0.14f, 0.16f, 1.3f, ElementShape.ROUNDED_RECT, true, "ADS\nLT", -1, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_RT] = ElementConfig(ID_BTN_RT, "Fire (RT)", ElementType.TRIGGER, 0.86f, 0.16f, 1.3f, ElementShape.ROUNDED_RECT, true, "FIRE\nRT", -2, Color.parseColor("#FF1744"))
        p.elements[ID_BTN_LB] = ElementConfig(ID_BTN_LB, "Tactical (LB)", ElementType.BUTTON, 0.28f, 0.16f, 1.0f, ElementShape.ROUNDED_RECT, true, "LB", Buttons.BTN_LB, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_RB] = ElementConfig(ID_BTN_RB, "Lethal (RB)", ElementType.BUTTON, 0.72f, 0.16f, 1.0f, ElementShape.ROUNDED_RECT, true, "RB", Buttons.BTN_RB, Color.parseColor("#00E5FF"))

        // Action Buttons
        p.elements[ID_BTN_Y] = ElementConfig(ID_BTN_Y, "Switch Weapon", ElementType.BUTTON, 0.65f, 0.42f, 1.0f, ElementShape.CIRCLE, true, "Y", Buttons.BTN_Y, Color.parseColor("#FFD600"))
        p.elements[ID_BTN_B] = ElementConfig(ID_BTN_B, "Crouch / Slide", ElementType.BUTTON, 0.72f, 0.50f, 1.0f, ElementShape.CIRCLE, true, "B", Buttons.BTN_B, Color.parseColor("#FF1744"))
        p.elements[ID_BTN_A] = ElementConfig(ID_BTN_A, "Jump", ElementType.BUTTON, 0.65f, 0.58f, 1.0f, ElementShape.CIRCLE, true, "A", Buttons.BTN_A, Color.parseColor("#00E676"))
        p.elements[ID_BTN_X] = ElementConfig(ID_BTN_X, "Reload / Interact", ElementType.BUTTON, 0.58f, 0.50f, 1.0f, ElementShape.CIRCLE, true, "X", Buttons.BTN_X, Color.parseColor("#2979FF"))

        // D-Pad for inventory/pings
        p.elements[ID_DPAD_UP] = ElementConfig(ID_DPAD_UP, "Ping / Up", ElementType.BUTTON, 0.38f, 0.42f, 0.9f, ElementShape.TRIANGLE_UP, true, "▲", Buttons.DPAD_UP, Color.WHITE)
        p.elements[ID_DPAD_DOWN] = ElementConfig(ID_DPAD_DOWN, "Emote / Down", ElementType.BUTTON, 0.38f, 0.58f, 0.9f, ElementShape.TRIANGLE_DOWN, true, "▼", Buttons.DPAD_DOWN, Color.WHITE)
        p.elements[ID_DPAD_LEFT] = ElementConfig(ID_DPAD_LEFT, "Heal / Left", ElementType.BUTTON, 0.32f, 0.50f, 0.9f, ElementShape.TRIANGLE_LEFT, true, "◀", Buttons.DPAD_LEFT, Color.WHITE)
        p.elements[ID_DPAD_RIGHT] = ElementConfig(ID_DPAD_RIGHT, "Equip / Right", ElementType.BUTTON, 0.44f, 0.50f, 0.9f, ElementShape.TRIANGLE_RIGHT, true, "▶", Buttons.DPAD_RIGHT, Color.WHITE)

        // System
        p.elements[ID_BTN_SELECT] = ElementConfig(ID_BTN_SELECT, "Scoreboard", ElementType.BUTTON, 0.44f, 0.16f, 0.85f, ElementShape.ROUNDED_RECT, true, "SCORE", Buttons.BTN_SELECT, Color.LTGRAY)
        p.elements[ID_BTN_GUIDE] = ElementConfig(ID_BTN_GUIDE, "Home", ElementType.BUTTON, 0.50f, 0.16f, 0.9f, ElementShape.CIRCLE, true, "⊚", Buttons.BTN_GUIDE, Color.parseColor("#00E5FF"))
        p.elements[ID_BTN_START] = ElementConfig(ID_BTN_START, "Options", ElementType.BUTTON, 0.56f, 0.16f, 0.85f, ElementShape.ROUNDED_RECT, true, "OPT", Buttons.BTN_START, Color.LTGRAY)

        return p
    }
}
