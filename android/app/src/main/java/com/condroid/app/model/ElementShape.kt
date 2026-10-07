package com.condroid.app.model

enum class ElementShape(val displayName: String, val symbol: String) {
    CIRCLE("Circle", "○"),
    SQUARE("Square", "▢"),
    ROUNDED_RECT("Rounded", "⬡"),
    TRIANGLE_UP("Triangle Up", "▲"),
    TRIANGLE_DOWN("Triangle Down", "▼"),
    TRIANGLE_LEFT("Triangle Left", "◀"),
    TRIANGLE_RIGHT("Triangle Right", "▶");

    fun next(): ElementShape {
        val values = entries
        val nextIdx = (ordinal + 1) % values.size
        return values[nextIdx]
    }

    companion object {
        fun fromString(name: String?): ElementShape {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CIRCLE
        }
    }
}
