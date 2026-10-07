package com.condroid.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.condroid.app.R
import com.condroid.app.model.LayoutPreset
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

class LayoutPresetAdapter(
    private var presets: List<LayoutPreset>,
    private var activePresetId: String,
    private val onPlay: (LayoutPreset) -> Unit,
    private val onCustomize: (LayoutPreset) -> Unit,
    private val onClone: (LayoutPreset) -> Unit,
    private val onDelete: (LayoutPreset) -> Unit
) : RecyclerView.Adapter<LayoutPresetAdapter.PresetViewHolder>() {

    class PresetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtTitle: TextView = view.findViewById(R.id.txtPresetTitle)
        val txtDesc: TextView = view.findViewById(R.id.txtPresetDesc)
        val badgeActive: TextView = view.findViewById(R.id.badgeActive)
        val badgeType: TextView = view.findViewById(R.id.badgeType)
        val btnPlay: MaterialButton = view.findViewById(R.id.btnPlay)
        val btnCustomize: MaterialButton = view.findViewById(R.id.btnCustomize)
        val btnClone: MaterialButton = view.findViewById(R.id.btnClone)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_layout_preset, parent, false)
        return PresetViewHolder(view)
    }

    override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
        val preset = presets[position]
        val isActive = preset.id == activePresetId
        val ctx = holder.itemView.context

        val dynamicPrimary = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#00E5FF"))
        val dynamicSecondary = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondary, Color.parseColor("#D500F9"))

        holder.txtTitle.text = preset.name
        holder.txtDesc.text = preset.description

        // Active Badge
        holder.badgeActive.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.badgeActive.setTextColor(dynamicPrimary)

        // Type Badge
        if (preset.isBuiltIn) {
            holder.badgeType.text = "BUILT-IN"
            holder.badgeType.setTextColor(dynamicPrimary)
            holder.btnDelete.visibility = View.GONE
        } else {
            holder.badgeType.text = "CUSTOM"
            holder.badgeType.setTextColor(dynamicSecondary)
            holder.btnDelete.visibility = View.VISIBLE
        }

        // Card tap launches controller
        holder.itemView.setOnClickListener {
            onPlay(preset)
        }

        holder.btnPlay.setOnClickListener {
            onPlay(preset)
        }

        holder.btnCustomize.setOnClickListener {
            onCustomize(preset)
        }

        holder.btnClone.setOnClickListener {
            onClone(preset)
        }

        holder.btnDelete.setOnClickListener {
            onDelete(preset)
        }
    }

    override fun getItemCount(): Int = presets.size

    fun updateData(newPresets: List<LayoutPreset>, newActiveId: String) {
        presets = newPresets
        activePresetId = newActiveId
        notifyDataSetChanged()
    }
}
