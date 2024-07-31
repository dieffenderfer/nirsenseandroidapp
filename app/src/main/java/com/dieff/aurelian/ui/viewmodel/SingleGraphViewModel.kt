package com.dieff.aurelian.ui.viewmodel

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dieff.aurelian.foregroundService.ble.ArgusPacket
import com.dieff.aurelian.foregroundService.ble.AurelianPacket
import com.dieff.aurelian.foregroundService.ble.BleManager
import com.dieff.aurelian.foregroundService.ble.Device
import com.dieff.aurelian.foregroundService.ble.Packet
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class SingleGraphViewModel : ViewModel() {

    private var currentDevice: Device? = null

    fun setDevice(device: Device) {
        currentDevice = device
        setupForDeviceType(device.deviceVersionInfo.deviceFamily)
    }

    //For graph management
    private lateinit var bufferMonitorJob: Job
    private val xMax: Int = 300
    private var everIncreasingLowerBound = 0f
    private var everIncreasingUpperBound = 0f
    private var everIncreasingX = 0f

    // Properties for smooth animation
    private var smoothAnimation = true
    private val _animationDelay = MutableLiveData<Long>(16L)
    val animationDelay: LiveData<Long> = _animationDelay
    fun setAnimationDelay(delay: Long) {
        _animationDelay.value = delay
    }

    // Toggle smooth animation
    fun setSmoothAnimation(enabled: Boolean) {
        smoothAnimation = enabled
    }

    // For debounce mechanism for buttons (prevent spamming of buttons)
    private var lastButtonClickTime = 0L
    private val buttonCooldown = 1000L

    data class ReadoutConfig(
        val label: String,
        val unit: String
    )

    var readoutConfigs: List<ReadoutConfig> = listOf(
        ReadoutConfig("StO2", "%"),
        ReadoutConfig("SpO2", "%"),
        ReadoutConfig("PR", "bpm"),
        ReadoutConfig("RR", "rpm")
    )


    fun setupForDeviceType(deviceType: Device.DeviceFamily) {
        when (deviceType) {
            Device.DeviceFamily.Argus -> setupArgus()
            Device.DeviceFamily.Aurelian -> setupAurelian()
            else -> setupDefault()
        }
    }

    private fun setupArgus() {
        smoothAnimation = true

        //TODO FIX_ME make it different based on argus 1 and argus 2+
        setAnimationDelay(16L)
        readoutConfigs = listOf(
            ReadoutConfig("StO2", "%"),
            ReadoutConfig("SpO2", "%"),
            ReadoutConfig("PR", "bpm"),
            ReadoutConfig("RR", "rpm")
        )
    }

    private fun setupAurelian() {
        smoothAnimation = false
        setAnimationDelay(18L)
        readoutConfigs = listOf(
            ReadoutConfig("Sampling Rate", "Hz"),
            ReadoutConfig("tDCS Imp", "Ω"),
            ReadoutConfig("tDCS Cur", "mA"),
            ReadoutConfig("tDCS Time", "s")
        )
    }

    private fun setupDefault() {
        smoothAnimation = true
        setAnimationDelay(16L)
        readoutConfigs = listOf(
            ReadoutConfig("Value 1", ""),
            ReadoutConfig("Value 2", ""),
            ReadoutConfig("Value 3", ""),
            ReadoutConfig("Value 4", "")
        )
    }

    fun setupLineChartDebug(lineChart: LineChart, lineChart2: LineChart) {
        Log.d("DBG", "Entered setupLineChartDebug")

        //Bottom Chart (PPG)
        val entries1 = ArrayList<Entry>()
        val entries2 = ArrayList<Entry>()

        val dataSet1 = LineDataSet(entries1, "Data 1")
        val dataSet2 = LineDataSet(entries2, "Data 2")

        dataSet1.color = Color.RED
        dataSet1.valueTextColor = Color.BLUE
        dataSet1.setDrawValues(false)
        dataSet1.setDrawFilled(false)
        dataSet1.setDrawCircles(false)
        dataSet1.fillColor = Color.GREEN
        dataSet1.mode = LineDataSet.Mode.LINEAR

        dataSet2.color = Color.RED
        dataSet2.valueTextColor = Color.BLUE
        dataSet2.setDrawValues(false)
        dataSet2.setDrawFilled(false)
        dataSet2.setDrawCircles(false)
        dataSet2.fillColor = Color.RED
        dataSet2.mode = LineDataSet.Mode.LINEAR

        val dataSets = ArrayList<ILineDataSet>()
        dataSets.add(dataSet1)
        dataSets.add(dataSet2)

        val lineData = LineData(dataSets)

        val xAxis = lineChart.xAxis

        xAxis.setAxisMinimum(0f)
        xAxis.setAxisMaximum(xMax.toFloat())

        lineChart.data = lineData
        lineChart.axisRight.isEnabled = false  // Hide right Y-axis
        lineChart.description.isEnabled = false  // Hide description label
        lineChart.legend.isEnabled = false //Hide legend-- we manually drew one in the xml
        lineChart.invalidate()

        //Top Chart (StO2)
        val entries1_2 = ArrayList<Entry>()
        val entries2_2 = ArrayList<Entry>()

        val dataSet1_2 = LineDataSet(entries1_2, "Data 1")
        val dataSet2_2 = LineDataSet(entries2_2, "Data 2")

        dataSet1_2.color = Color.BLUE
        dataSet1_2.valueTextColor = Color.BLUE
        dataSet1_2.setDrawValues(false)
        dataSet1_2.setDrawFilled(false)
        dataSet1_2.setDrawCircles(false)
        dataSet1_2.fillColor = Color.GREEN
        dataSet1_2.mode = LineDataSet.Mode.LINEAR

        dataSet2_2.color = Color.RED//Color.MAGENTA
        dataSet2_2.valueTextColor = Color.BLUE
        dataSet2_2.setDrawValues(false)
        dataSet2_2.setDrawFilled(false) //true looked cool.
        dataSet2_2.setDrawCircles(false)
        dataSet2_2.fillColor = Color.RED
        dataSet2_2.mode = LineDataSet.Mode.LINEAR

        val dataSets_2 = ArrayList<ILineDataSet>()
        dataSets_2.add(dataSet1_2)
        //dataSets_2.add(dataSet2_2)

        val lineData_2 = LineData(dataSets_2)

        val xAxis_2 = lineChart2.xAxis

        xAxis_2.setAxisMinimum(everIncreasingLowerBound)
        xAxis_2.setAxisMaximum(everIncreasingUpperBound)

        lineChart2.data = lineData_2
        lineChart2.axisRight.isEnabled = false  // Hide right Y-axis
        lineChart2.description.isEnabled = false  // Hide description label
        lineChart2.legend.isEnabled = false //Hide legend-- we manually drew one in the xml
        lineChart2.invalidate()
    }

    //Debounce mechanism to prevent spamming of buttons
    private fun canPerformAction(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastButtonClickTime >= buttonCooldown) {
            lastButtonClickTime = currentTime
            return true
        }
        return false
    }

    fun captureYBounds(lineChart: LineChart) {
        if (!canPerformAction()) return

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

        val yAxis = lineChart.axisLeft
        yAxis.axisMinimum = minY
        yAxis.axisMaximum = maxY
        lineChart.invalidate()
    }

    fun resetAutoscale(lineChart: LineChart) {
        if (!canPerformAction()) return

        val yAxis = lineChart.axisLeft
        yAxis.resetAxisMaximum()
        yAxis.resetAxisMinimum()
        lineChart.invalidate()
    }


    @SuppressLint("NullSafeMutableLiveData")
    fun toggleSampling() {
        currentDevice?.let { device ->
            Log.d("DBG", "Button Pressed: Toggle Sampling")

            if (device.isStreaming.value) {
                BleManager.stopSamplingDevice(device)
            } else {
                BleManager.startSamplingDevice(device)
            }
        }
    }

    fun exportFlashData() {
        currentDevice?.let { device ->
            Log.d("DBG", "Button Pressed: Export Flash Data")
            BleManager.sendExportFlashDataCommandDevice(device)
        }
    }

    fun clearFlash() {
        currentDevice?.let { device ->
            Log.d("DBG", "Button Pressed: Clear Flash Data")
            BleManager.sendClearFlashCommandDevice(device)

            Handler(Looper.getMainLooper()).postDelayed({
                BleManager.enableSaveModeDevice(device)
            }, 1000) // wait 1 second
        }
    }

    @SuppressLint("MissingPermission")
    fun receivePacketArrayFromDataAggregator(lineChart: LineChart, lineChart2: LineChart, readoutBox1Middle: TextView, readoutBox2Middle: TextView, readoutBox3Middle: TextView, readoutBox4Middle: TextView) {
        Log.d("DBG", "notHomeFragment - Entered receivePacketArrayFromDataAggregator")

        var existingDatasets = lineChart.lineData.dataSets
        val pingDataset = existingDatasets[0]
        val pongDataset = existingDatasets[1]

        var existingDatasets2 = lineChart2.lineData.dataSets
        val lineChart2Dataset = existingDatasets2[0]

        bufferMonitorJob = viewModelScope.launch {
            var pingNotPong = true

            currentDevice?.dataAggregator?.graphingDataFlow?.collect { graphPackets ->
                Log.d("DBG", "notHomeFragment I got the message on the channel... from the dataAggregator!")

                // Digital readouts
                val firstPacket = graphPackets.firstOrNull()
                updateReadouts(firstPacket, readoutBox1Middle, readoutBox2Middle, readoutBox3Middle, readoutBox4Middle)

                for (graphPacket in graphPackets) {
                    //drifting graph / graph 2
                    val newEntry2 = when (graphPacket) {
                        is ArgusPacket -> Entry(everIncreasingX.toFloat(), graphPacket.stO2.toFloat())
                        is AurelianPacket -> Entry(everIncreasingX.toFloat(), graphPacket.eegC1.toFloat())
                        else -> null
                    }
                    newEntry2?.let { lineChart2Dataset.addEntry(it) }
                    everIncreasingX += 1
                    everIncreasingUpperBound += 1

                    if (lineChart2Dataset.entryCount > xMax) {
                        lineChart2Dataset.removeFirst()
                        everIncreasingLowerBound += 1
                    }

                    val xAxis_2 = lineChart2.xAxis

                    xAxis_2.setAxisMinimum(everIncreasingLowerBound)
                    xAxis_2.setAxisMaximum(everIncreasingUpperBound)

                    Log.d("DBG_PP", "****** pingNotPong = $pingNotPong ******")
                    // Test for First Pass
                    if (pingDataset.entryCount + pongDataset.entryCount < xMax + 1) {
                        Log.d("DBG_PP", "FIRST_PASS: pingDataset is beforeDataSet")
                        Log.d("DBG_PP", "  pingDataset.entryCount = ${pingDataset.entryCount} pongDataset.entryCount = ${pongDataset.entryCount}\"")
                        // Add new data as an Entry to the end of the beforeDataset
                        val newEntry = when (graphPacket) {
                            is ArgusPacket -> Entry(pingDataset.entryCount.toFloat(), graphPacket.ppgWaveform.toFloat())
                            is AurelianPacket -> Entry(pingDataset.entryCount.toFloat(), graphPacket.eegC2.toFloat())
                            else -> null
                        }
                        newEntry?.let {
                            Log.d("DBG_PP", "  ADD ENTRY TO pingDataSet $it")
                            pingDataset.addEntry(it)
                        }
                        Log.d("DBG_PP", "  pingDataset.entryCount = ${pingDataset.entryCount} pongDataset.entryCount = ${pongDataset.entryCount}\"")
                        if (pingDataset.entryCount == xMax + 1) {
                            pingNotPong = false // switch to pongDataset being before buffer
                            Log.d("DBG_PP", "  Toggled pingNotPong to: $pingNotPong")
                        }
                    } else {
                        if (pingNotPong) {  // pingDataset is before buffer
                            Log.d("DBG_PP", "ODD_PASS: pingDataset is beforeDataSet")
                            Log.d("DBG_PP", "  pingDataset.entryCount = ${pingDataset.entryCount} pongDataset.entryCount = ${pongDataset.entryCount}\"")
                            // Add new data as an Entry to the end of the beforeDataset
                            val newEntry = when (graphPacket) {
                                is ArgusPacket -> Entry(pingDataset.entryCount.toFloat(), graphPacket.ppgWaveform.toFloat())
                                is AurelianPacket -> Entry(pingDataset.entryCount.toFloat(), graphPacket.eegC2.toFloat())
                                else -> null
                            }
                            newEntry?.let {
                                Log.d("DBG_PP", "  ADD ENTRY TO pingDataSet $it")
                                pingDataset.addEntry(it)
                            }
                            Log.d("DBG_PP", "  pingDataset.entryCount = ${pingDataset.entryCount} pongDataset.entryCount = ${pongDataset.entryCount}\"")
                            if (pongDataset.entryCount > 0) {
                                Log.d("DBG_PP", "  REMOVE ENTRY FROM pongDataSet: entryCount =  ${pongDataset.entryCount}")
                                pongDataset.removeFirst()
                                if (pongDataset.entryCount == 0) {
                                    pingNotPong = false  // switch to pongDataset being before buffer
                                    Log.d("DBG_PP", "  Toggled pingNotPong to: $pingNotPong")
                                }
                            }
                        } else { // pongDataset is before buffer
                            Log.d("DBG_PP", "EVN_PASS: pongDataset is beforeDataSet")
                            Log.d("DBG_PP", "  pingDataset.entryCount = ${pingDataset.entryCount} pongDataset.entryCount = ${pongDataset.entryCount}\"")
                            val newEntry = when (graphPacket) {
                                is ArgusPacket -> Entry(pongDataset.entryCount.toFloat(), graphPacket.ppgWaveform.toFloat())
                                is AurelianPacket -> Entry(pongDataset.entryCount.toFloat(), graphPacket.eegC2.toFloat())
                                else -> null
                            }
                            newEntry?.let {
                                Log.d("DBG_PP", "  ADD ENTRY TO pongDataSet $it")
                                pongDataset.addEntry(it)
                            }
                            Log.d("DBG_PP", "  pingDataset.entryCount = ${pingDataset.entryCount} pongDataset.entryCount = ${pongDataset.entryCount}\"")
                            if (pingDataset.entryCount > 0) {
                                Log.d(
                                    "DBG_PP",
                                    "  REMOVE ENTRY FROM pingDataSet: entryCount =  ${pingDataset.entryCount}"
                                )
                                pingDataset.removeFirst()
                                Log.d(
                                    "DBG_PP",
                                    "  pingDataset.entryCount = ${pingDataset.entryCount} pongDataset.entryCount = ${pongDataset.entryCount}\""
                                )
                                if (pingDataset.entryCount == 0) {
                                    pingNotPong = true // switch to pingDataset to before buffer
                                    Log.d("DBG_PP", "  Toggled pingNotPong to: $pingNotPong")
                                }
                            }
                        }
                    }
                    // Notify the chart that the data has changed
                    Log.d("DBG_PP","  NotifyDataChanged Statements")
                    lineChart.data.notifyDataChanged()
                    lineChart.notifyDataSetChanged()

                    // Notify the second chart that the data has changed
                    lineChart2.data.notifyDataChanged()
                    lineChart2.notifyDataSetChanged()

                    if (smoothAnimation) {
                        // If smooth animation is enabled, update the charts after each point
                        lineChart.invalidate()
                        lineChart2.invalidate()
                        animationDelay.value?.let { delay(it) }
                    }
                }

                if (!smoothAnimation) {
                    // If smooth animation is disabled, update the charts once after processing all points
                    lineChart.invalidate()
                    lineChart2.invalidate()
                }
            }
        }

        Log.d("DBG", "notHomeFragment - Exited receivePacketArrayFromDataAggregator")
    }

    private fun updateReadouts(
        packet: Packet?,
        readoutBox1Middle: TextView,
        readoutBox2Middle: TextView,
        readoutBox3Middle: TextView,
        readoutBox4Middle: TextView
    ) {
        when (packet) {
            is ArgusPacket -> {
                readoutBox1Middle.text = if (packet.stO2 == 0u.toUByte()) "--" else packet.stO2.toString()
                readoutBox2Middle.text = if (packet.spO2 == 0u.toUByte()) "--" else packet.spO2.toString()
                readoutBox3Middle.text = if (packet.pulseRate == 0u.toUByte()) "--" else packet.pulseRate.toString()
                readoutBox4Middle.text = if (packet.respiratoryRate == 0u.toUByte()) "--" else packet.respiratoryRate.toString()
            }
            is AurelianPacket -> {
                val samplingRate = 1/(packet.timeElapsed / 32768)
                val formattedSamplingRate = String.format("%.1f", samplingRate)

                //Show red for bad sampling rate values
                if (samplingRate <= 200 || samplingRate >= 300) {
                    readoutBox1Middle.setTextColor(Color.RED)
                }
                else {
                    if (readoutBox1Middle.currentTextColor != Color.BLACK) {
                        readoutBox1Middle.setTextColor(Color.BLACK)
                    }
                }

                readoutBox1Middle.text = if (samplingRate == 0.0) "--" else formattedSamplingRate
                readoutBox2Middle.text = if (packet.tdcsImpedance == 0u.toUShort()) "--" else packet.tdcsImpedance.toString()
                readoutBox3Middle.text = if (packet.tdcsCurrent == 0u.toUShort()) "--" else packet.tdcsCurrent.toString()
                readoutBox4Middle.text = if (packet.tdcsOnTime == 0u.toUShort()) "--" else packet.tdcsOnTime.toString()
            }
        }
    }

    fun cancelBufferMonitorJob() {
        bufferMonitorJob.cancel()
    }
}