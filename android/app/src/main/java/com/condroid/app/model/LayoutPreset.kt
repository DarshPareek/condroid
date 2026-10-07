package com.condroid.app.model

import org.json.JSONArray
import org.json.JSONObject

data class LayoutPreset(
    val id: String,
    var name: String,
    var description: String,
    val isBuiltIn: Boolean,
    val elements: MutableMap<String, ElementConfig> = mutableMapOf()
) {
    fun deepCopy(newId: String, newName: String): LayoutPreset {
        val copiedElements = mutableMapOf<String, ElementConfig>()
        for ((k, v) in elements) {
            copiedElements[k] = v.copy()
        }
        return LayoutPreset(
            id = newId,
            name = newName,
            description = "Custom layout based on $name",
            isBuiltIn = false,
            elements = copiedElements
        )
    }

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("id", id)
        root.put("name", name)
        root.put("description", description)
        root.put("isBuiltIn", isBuiltIn)

        val elementsArray = JSONArray()
        for (el in elements.values) {
            elementsArray.put(el.toJson())
        }
        root.put("elements", elementsArray)
        return root
    }

    companion object {
        fun fromJson(json: JSONObject): LayoutPreset {
            val id = json.getString("id")
            val name = json.optString("name", "Custom Layout")
            val desc = json.optString("description", "")
            val isBuiltIn = json.optBoolean("isBuiltIn", false)

            val preset = LayoutPreset(id, name, desc, isBuiltIn)
            val elementsArray = json.optJSONArray("elements")
            if (elementsArray != null) {
                for (i in 0 until elementsArray.length()) {
                    val elObj = elementsArray.getJSONObject(i)
                    val el = ElementConfig.fromJson(elObj)
                    preset.elements[el.id] = el
                }
            }
            return preset
        }
    }
}
