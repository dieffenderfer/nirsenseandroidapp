package com.dieff.aurelian.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dieff.aurelian.databinding.ScanResultEntryBinding
import com.dieff.aurelian.foregroundService.ble.MiniScanResult

class ScanResultAdapter(
    private val miniScanResults: List<MiniScanResult>,
    private val onSelectionChanged: (List<Int>) -> Unit
) : RecyclerView.Adapter<ScanResultAdapter.ViewHolder>() {

    private val selectedPositions = mutableSetOf<Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ScanResultEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = miniScanResults.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = miniScanResults[position]
        holder.bind(item, selectedPositions.contains(position))
    }

    inner class ViewHolder(private val binding: ScanResultEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                handleSelection(adapterPosition)
            }
            binding.deviceCheckbox.setOnClickListener {
                handleSelection(adapterPosition)
            }
        }

        fun bind(miniScanResult: MiniScanResult, isSelected: Boolean) {
            binding.deviceName.text = miniScanResult.bleDevName
            binding.macAddress.text = miniScanResult.bleDevAddr
            binding.signalStrength.text = "${miniScanResult.bleDevRssi} dBm"
            binding.deviceCheckbox.isChecked = isSelected
        }
    }

    private fun handleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
        onSelectionChanged(selectedPositions.toList())
    }

    fun getSelectedPositions(): List<Int> = selectedPositions.toList()

    fun clearSelections() {
        selectedPositions.clear()
        notifyDataSetChanged()
    }
}