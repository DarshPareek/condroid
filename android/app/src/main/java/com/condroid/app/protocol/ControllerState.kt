package com.condroid.app.protocol

data class ControllerState(
    var seq: Int = 0,
    var timestampMs: Int = 0,
    var buttons: Int = 0,
    var leftTrigger: Int = 0,
    var rightTrigger: Int = 0,
    var leftStickX: Short = 0,
    var leftStickY: Short = 0,
    var rightStickX: Short = 0,
    var rightStickY: Short = 0,
    var gyroX: Short = 0,
    var gyroY: Short = 0,
    var gyroZ: Short = 0
) {
    fun setButton(mask: Int, pressed: Boolean) {
        buttons = if (pressed) {
            buttons or mask
        } else {
            buttons and mask.inv()
        }
    }

    fun isButtonPressed(mask: Int): Boolean = (buttons and mask) != 0

    fun reset() {
        buttons = 0
        leftTrigger = 0
        rightTrigger = 0
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
        gyroX = 0
        gyroY = 0
        gyroZ = 0
    }

    fun copyFrom(other: ControllerState) {
        this.seq = other.seq
        this.timestampMs = other.timestampMs
        this.buttons = other.buttons
        this.leftTrigger = other.leftTrigger
        this.rightTrigger = other.rightTrigger
        this.leftStickX = other.leftStickX
        this.leftStickY = other.leftStickY
        this.rightStickX = other.rightStickX
        this.rightStickY = other.rightStickY
        this.gyroX = other.gyroX
        this.gyroY = other.gyroY
        this.gyroZ = other.gyroZ
    }
}
