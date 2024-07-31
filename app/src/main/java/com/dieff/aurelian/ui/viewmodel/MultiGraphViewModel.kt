package com.dieff.aurelian.ui.viewmodel

import android.graphics.Color
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dieff.aurelian.foregroundService.ble.*
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class MultiGraphViewModel : ViewModel() {
    private val _connectedDevices = MutableStateFlow<List<Device>>(emptyList())
    val connectedDevices: StateFlow<List<Device>> = _connectedDevices.asStateFlow()

    private val xMax: Int = 300
    private val everIncreasingX = mutableMapOf<Device, Float>()
    private val everIncreasingLowerBound = mutableMapOf<Device, Float>()
    private val everIncreasingUpperBound = mutableMapOf<Device, Float>()

    private val _animationDelay = MutableStateFlow(16L)
    val animationDelay: StateFlow<Long> = _animationDelay.asStateFlow()

    data class ReadoutConfig(val label: String, val unit: String)

    private val defaultReadoutConfigs = listOf(
        ReadoutConfig("Value 1", ""),
        ReadoutConfig("Value 2", ""),
        ReadoutConfig("Value 3", ""),
        ReadoutConfig("Value 4", "")
    )

    private val readoutConfigs = mutableMapOf<Device.DeviceFamily, List<ReadoutConfig>>()

    init {
        readoutConfigs[Device.DeviceFamily.Argus] = listOf(
            ReadoutConfig("StO2", "%"),
            ReadoutConfig("SpO2", "%"),
            ReadoutConfig("PR", "bpm"),
            ReadoutConfig("RR", "rpm")
        )
        readoutConfigs[Device.DeviceFamily.Aurelian] = listOf(
            ReadoutConfig("Sampling Rate", "Hz"),
            ReadoutConfig("tDCS Imp", "Ω"),
            ReadoutConfig("tDCS Cur", "mA"),
            ReadoutConfig("tDCS Time", "s")
        )
    }

    fun updateConnectedDevices(devices: List<Device>) {
        viewModelScope.launch {
            _connectedDevices.value = devices
        }
    }

    fun getReadoutConfigsForDevice(deviceFamily: Device.DeviceFamily): List<ReadoutConfig> {
        return readoutConfigs[deviceFamily] ?: defaultReadoutConfigs
    }

    fun setupLineChart(lineChart: LineChart, deviceType: Device.DeviceFamily) {
        val entries1 = ArrayList<Entry>()
        val entries2 = ArrayList<Entry>()

        val dataSet1 = LineDataSet(entries1, "Data 1").apply {
            color = if (deviceType == Device.DeviceFamily.Argus) Color.RED else Color.BLUE
            setDrawValues(false)
            setDrawCircles(false)
            mode = LineDataSet.Mode.LINEAR
        }

        val dataSet2 = LineDataSet(entries2, "Data 2").apply {
            color = Color.RED
            setDrawValues(false)
            setDrawCircles(false)
            mode = LineDataSet.Mode.LINEAR
        }

        val dataSets = ArrayList<ILineDataSet>()
        dataSets.add(dataSet1)
        dataSets.add(dataSet2)

        val lineData = LineData(dataSets)

        lineChart.apply {
            data = lineData
            description.isEnabled = false
            legend.isEnabled = false
            axisRight.isEnabled = false
            xAxis.axisMinimum = 0f
            xAxis.axisMaximum = xMax.toFloat()
            invalidate()
        }
    }

    fun startMonitoringDevice(device: Device, lineChart1: LineChart, lineChart2: LineChart, readoutBoxes: List<TextView>) {
        everIncreasingX[device] = 0f
        everIncreasingLowerBound[device] = 0f
        everIncreasingUpperBound[device] = 0f

        viewModelScope.launch {
            var pingNotPong = true

            val dataset1 = lineChart1.data.dataSets[0] as LineDataSet
            val dataset2 = lineChart1.data.dataSets[1] as LineDataSet
            val lineChart2Dataset = lineChart2.data.dataSets[0] as LineDataSet

            device.packetFlow.collect { graphPacket ->
                updateReadouts(graphPacket, readoutBoxes)
                updateCharts(device, graphPacket, lineChart1, lineChart2, dataset1, dataset2, lineChart2Dataset, pingNotPong)
                pingNotPong = !pingNotPong

                lineChart1.invalidate()
                lineChart2.invalidate()
            }
        }
    }

    private fun updateReadouts(packet: Any?, readoutBoxes: List<TextView>) {
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

    private fun updateCharts(device: Device, graphPacket: Any, lineChart1: LineChart, lineChart2: LineChart,
                             dataset1: LineDataSet, dataset2: LineDataSet, lineChart2Dataset: LineDataSet, pingNotPong: Boolean) {
        val newEntry2 = when (graphPacket) {
            is ArgusPacket -> Entry(everIncreasingX[device]!!, graphPacket.stO2.toFloat())
            is AurelianPacket -> Entry(everIncreasingX[device]!!, graphPacket.eegC1.toFloat())
            else -> null
        }
        newEntry2?.let { lineChart2Dataset.addEntry(it) }
        everIncreasingX[device] = everIncreasingX[device]!! + 1
        everIncreasingUpperBound[device] = everIncreasingUpperBound[device]!! + 1

        if (lineChart2Dataset.entryCount > xMax) {
            lineChart2Dataset.removeFirst()
            everIncreasingLowerBound[device] = everIncreasingLowerBound[device]!! + 1
        }

        lineChart2.xAxis.axisMinimum = everIncreasingLowerBound[device]!!
        lineChart2.xAxis.axisMaximum = everIncreasingUpperBound[device]!!

        val activeDataset = if (pingNotPong) dataset1 else dataset2
        val inactiveDataset = if (pingNotPong) dataset2 else dataset1

        val newEntry = when (graphPacket) {
            is ArgusPacket -> Entry(activeDataset.entryCount.toFloat(), graphPacket.ppgWaveform.toFloat())
            is AurelianPacket -> Entry(activeDataset.entryCount.toFloat(), graphPacket.eegC2.toFloat())
            else -> null
        }
        newEntry?.let { activeDataset.addEntry(it) }

        if (activeDataset.entryCount > xMax) {
            inactiveDataset.clear()
        }

        lineChart1.data.notifyDataChanged()
        lineChart1.notifyDataSetChanged()
        lineChart2.data.notifyDataChanged()
        lineChart2.notifyDataSetChanged()
    }

    fun toggleSampling(device: Device) {
        if (device.isStreaming.value) {
            BleManager.stopSamplingDevice(device)
        } else {
            BleManager.startSamplingDevice(device)
        }
    }

    fun captureYBounds(lineChart: LineChart) {
        val data = lineChart.data
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (dataSet in data.dataSets) {
            for (i in 0 until dataSet.entryCount) {
                val entry = dataSet.getEntryForIndex(i)
                minY = min(minY, entry.y)
                maxY = max(maxY, entry.y)
            }
        }

        lineChart.axisLeft.apply {
            axisMinimum = minY
            axisMaximum = maxY
        }
        lineChart.invalidate()
    }

    fun resetAutoscale(lineChart: LineChart) {
        lineChart.axisLeft.apply {
            resetAxisMaximum()
            resetAxisMinimum()
        }
        lineChart.invalidate()
    }
}