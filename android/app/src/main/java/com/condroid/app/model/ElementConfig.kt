package com.condroid.app.model

import org.json.JSONObject

data class ElementConfig(
    val id: String,
    var name: String,
    val type: ElementType,
    var xRatio: Float,
    var yRatio: Float,
    var scale: Float = 1.0f,
    var shape: ElementShape = ElementShape.CIRCLE,
    var visible: Boolean = true,
    var label: String = "",
    val mask: Int = 0,
    var defaultColor: Int = 0
) {
    fun copy(): ElementConfig {
        return ElementConfig(
            id = id,
            name = name,
            type = type,
            xRatio = xRatio,
            yRatio = yRatio,
            scale = scale,
            shape = shape,
            visible = visible,
            label = label,
            mask = mask,
            defaultColor = defaultColor
        )
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("type", type.name)
            put("xRatio", xRatio.toDouble())
            put("yRatio", yRatio.toDouble())
            put("scale", scale.toDouble())
            put("shape", shape.name)
            put("visible", visible)
            put("label", label)
            put("mask", mask)
            put("defaultColor", defaultColor)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ElementConfig {
            val id = json.getString("id")
            val name = json.optString("name", id)
            val typeStr = json.optString("type", ElementType.BUTTON.name)
            val type = try { ElementType.valueOf(typeStr) } catch (_: Exception) { ElementType.BUTTON }
            val xRatio = json.optDouble("xRatio", 0.5).toFloat()
            val yRatio = json.optDouble("yRatio", 0.5).toFloat()
            val scale = json.optDouble("scale", 1.0).toFloat().coerceIn(0.4f, 2.5f)
            val shapeStr = json.optString("shape", ElementShape.CIRCLE.name)
            val shape = ElementShape.fromString(shapeStr)
            val visible = json.optBoolean("visible", true)
            val label = json.optString("label", "")
            val mask = json.optInt("mask", 0)
            val defaultColor = json.optInt("defaultColor", 0)

            return ElementConfig(
                id = id,
                name = name,
                type = type,
                xRatio = xRatio,
                yRatio = yRatio,
                scale = scale,
                shape = shape,
                visible = visible,
                label = label,
                mask = mask,
                defaultColor = defaultColor
            )
        }
    }
}
