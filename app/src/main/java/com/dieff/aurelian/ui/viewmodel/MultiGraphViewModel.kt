package com.dieff.aurelian.ui.viewmodel

import android.graphics.Color
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dieff.aurelian.foregroundService.ble.*
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MultiGraphViewModel : ViewModel() {
    private val _connectedDevices = MutableStateFlow<List<Device>>(emptyList())
    val connectedDevices: StateFlow<List<Device>> = _connectedDevices.asStateFlow()

    private val monitoringJobs = mutableMapOf<Device, Job>()

    private val xMax: Int = 100 // Reduced for mini graphs
    private val everIncreasingX = mutableMapOf<Device, Float>()

    fun setupMiniLineChart(lineChart: LineChart, device: Device) {
        val entries = ArrayList<Entry>()
        val dataSet = LineDataSet(entries, "Data").apply {
            color = if (device.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Argus) Color.RED else Color.BLUE
            setDrawValues(false)
            setDrawCircles(false)
            mode = LineDataSet.Mode.LINEAR
        }

        val lineData = LineData(dataSet)

        lineChart.apply {
            data = lineData
            description.isEnabled = false
            legend.isEnabled = false
            axisRight.isEnabled = false
            xAxis.axisMinimum = 0f
            xAxis.axisMaximum = xMax.toFloat()
            setTouchEnabled(false)
            invalidate()
        }
    }

    fun startMonitoringDevice(device: Device, lineChart1: LineChart, lineChart2: LineChart, readoutBoxes: List<TextView>) {
        everIncreasingX[device] = 0f

        monitoringJobs[device] = viewModelScope.launch {
            device.packetFlow.collect { graphPacket ->
                updateReadouts(graphPacket, readoutBoxes)
                updateCharts(device, graphPacket, lineChart1, lineChart2)
            }
        }
    }

    fun updateReadouts(packet: Any?, readoutBoxes: List<TextView>) {
        when (packet) {
            is ArgusPacket -> {
                readoutBoxes[0].text = if (packet.stO2 == 0u.toUByte()) "--" else packet.stO2.toString()
                readoutBoxes[1].text = if (packet.spO2 == 0u.toUByte()) "--" else packet.spO2.toString()
                readoutBoxes[2].text = if (packet.pulseRate == 0u.toUByte()) "--" else packet.pulseRate.toString()
                readoutBoxes[3].text = if (packet.respiratoryRate == 0u.toUByte()) "--" else packet.respiratoryRate.toString()
            }
            is AurelianPacket -> {
                val samplingRate = 1 / (packet.timeElapsed / 32768.0)
                val formattedSamplingRate = String.format("%.1f", samplingRate)
                readoutBoxes[0].text = if (samplingRate == 0.0) "--" else formattedSamplingRate
                readoutBoxes[1].text = if (packet.tdcsImpedance == 0u.toUShort()) "--" else packet.tdcsImpedance.toString()
                readoutBoxes[2].text = if (packet.tdcsCurrent == 0u.toUShort()) "--" else packet.tdcsCurrent.toString()
                readoutBoxes[3].text = if (packet.tdcsOnTime == 0u.toUShort()) "--" else packet.tdcsOnTime.toString()
            }
        }
    }

    private fun updateCharts(device: Device, graphPacket: Any, lineChart1: LineChart, lineChart2: LineChart) {
        val dataset1 = lineChart1.data.dataSets[0] as LineDataSet
        val dataset2 = lineChart2.data.dataSets[0] as LineDataSet

        val newEntry1 = when (graphPacket) {
            is ArgusPacket -> Entry(everIncreasingX[device]!!, graphPacket.ppgWaveform.toFloat())
            is AurelianPacket -> Entry(everIncreasingX[device]!!, graphPacket.eegC2.toFloat())
            else -> null
        }

        val newEntry2 = when (graphPacket) {
            is ArgusPacket -> Entry(everIncreasingX[device]!!, graphPacket.stO2.toFloat())
            is AurelianPacket -> Entry(everIncreasingX[device]!!, graphPacket.eegC1.toFloat())
            else -> null
        }

        newEntry1?.let { dataset1.addEntry(it) }
        newEntry2?.let { dataset2.addEntry(it) }

        everIncreasingX[device] = everIncreasingX[device]!! + 1

        if (dataset1.entryCount > xMax) {
            dataset1.removeEntry(0)
        }

        if (dataset2.entryCount > xMax) {
            dataset2.removeEntry(0)
        }

        lineChart1.data.notifyDataChanged()
        lineChart1.notifyDataSetChanged()
        lineChart1.invalidate()

        lineChart2.data.notifyDataChanged()
        lineChart2.notifyDataSetChanged()
        lineChart2.invalidate()
    }

    fun stopMonitoringAllDevices() {
        monitoringJobs.values.forEach { it.cancel() }
        monitoringJobs.clear()
    }
}