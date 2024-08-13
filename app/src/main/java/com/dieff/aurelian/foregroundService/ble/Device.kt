package com.dieff.aurelian.foregroundService.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.util.Log
import com.dieff.aurelian.AppConfig.appVersion
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

@SuppressLint("MissingPermission")
class Device(
    var bluetoothGatt: BluetoothGatt,
    var filename: String,
    initialStatus: BleManager.SetupState
) {

    val macAddressString: String = bluetoothGatt.device.address
    val macAddress: Long = macAddressToLong(macAddressString)
    val name: String = bluetoothGatt.device.name ?: "Unknown Device"

    var deviceVersionInfo: DeviceVersionInfo = DeviceVersionInfo("-1", 0, DeviceFamily.Unknown, 0)

    var deviceBusy = true
    var streamReady = false
    var hasCompletedSetupBefore: Boolean = false

    var index: Int = 1
    var captureTimePreview: Instant? = null
    var timerBitsPreview: UInt = 0u

    var historyIndex: Int = 1
    var isStreamingStoredData: Boolean = false
    var historyFilename: String = "NIRSense"
    var captureTimeStored: Instant? = null
    var timerBitsStored: UInt = 0u

    //For Index value in CSV
    var previewIndex: Int = 0
    var storedIndex: Int = 0

    var battery: Int = -1

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _status = MutableStateFlow(initialStatus)
    val status: StateFlow<BleManager.SetupState> = _status.asStateFlow()

    private val _isDownloadComplete = MutableStateFlow(false)
    val isDownloadComplete: StateFlow<Boolean> = _isDownloadComplete.asStateFlow()

    private val _totalPackets = MutableStateFlow(0)
    val totalPackets: StateFlow<Int> = _totalPackets.asStateFlow()
    private val _currentPacketv2 = MutableStateFlow(0)
    val currentPacketv2: StateFlow<Int> = _currentPacketv2.asStateFlow()

    private val _packetFlow = MutableSharedFlow<Packet>(replay = 0, extraBufferCapacity = 64)
    val packetFlow = _packetFlow.asSharedFlow()

    val dataAggregator = DeviceDataAggregator()

    val progressPercent: Int
        get() = if (_totalPackets.value > 0) (_currentPacketv2.value * 100) / _totalPackets.value else 0

    val metadataText: String
        get() {
            val deviceType = when (deviceVersionInfo.deviceFamily) {
                DeviceFamily.Argus -> "NIRSense Argus"
                DeviceFamily.Aerie -> "NIRSense Aerie"
                DeviceFamily.EP -> "NIRSense EP"
                DeviceFamily.Aurelian -> "NIRSense Aurelian"
                DeviceFamily.Aeolus -> "NIRSense Aeolus"
                DeviceFamily.AeolusICG -> "NIRSense AeolusICG"
                DeviceFamily.Unknown -> "Unknown"
            }

            val deviceId = name.split(" ").lastOrNull() ?: ""
            val nvmId = deviceVersionInfo.nvmVersion.toLong()
            val firmware = deviceVersionInfo.firmwareVersion

            return "{\"Device_Type\":\"$deviceType\",\"Device_ID\":\"$deviceId\",\"Device_NVM_ID\":$nvmId,\"Firmware\":\"$firmware\",\"App_Version\":\"$appVersion\"}"
        }

    fun setConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    fun setTotalPackets(value: Int) {
        _totalPackets.value = value
    }

    fun setCurrentPacketv2(value: Int) {
        _currentPacketv2.value = value
    }

    fun setIsStreaming(value: Boolean) {
        _isStreaming.value = value
    }

    fun setStatus(value: BleManager.SetupState) {
        _status.value = value
    }

    fun setDownloadComplete(complete: Boolean) {
        _isDownloadComplete.value = complete
    }

    suspend fun emitPacket(packet: Packet) {
        _packetFlow.emit(packet)
    }

    fun getDeviceInfo(): String {
        return "Device Filename: $filename, MAC Address String: $macAddressString, " +
                "MAC Address: ${macAddress.toString(16).uppercase()}, " +
                "Firmware Version: ${deviceVersionInfo.firmwareVersion}, " +
                "Device Family: ${deviceVersionInfo.deviceFamily}, " +
                "Argus Version: ${deviceVersionInfo.argusVersion}"
    }

    enum class ConnectionStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    data class DeviceVersionInfo(
        var firmwareVersion: String,
        var nvmVersion: Int,
        var deviceFamily: DeviceFamily,
        var argusVersion: Int
    )

    enum class DeviceFamily(val value: Int) {
        Aerie(0x01),
        EP(0x02),
        Argus(0x03),
        Aurelian(0x04),
        Aeolus(0x05),
        AeolusICG(0x06),
        Unknown(0xFF);

        companion object {
            fun fromInt(value: Int) = values().find { it.value == value } ?: Unknown
        }
    }

    companion object {
        fun macAddressToLong(macAddress: String): Long =
            macAddress.split(":")
                .map { it.toInt(16).toLong() }
                .reduce { acc, value -> (acc shl 8) or value }
    }

    inner class DeviceDataAggregator {
        private fun determineArraySizeGraphing(): Int {
            return when (deviceVersionInfo.deviceFamily) {
                DeviceFamily.Aurelian -> 5
                DeviceFamily.Argus -> {
                    if (deviceVersionInfo.argusVersion >= 2) 6 else 1
                }
                DeviceFamily.Aerie -> 1
                else -> 1
            }
        }

        private val ARRAY_SIZE_GRAPHING = determineArraySizeGraphing()
        private val ARRAY_SIZE_SAVING = ARRAY_SIZE_GRAPHING * 100
        private val MAX_BATCH_SIZE = 1000

        private val dbAggregateArrayGraphing: Array<Packet?> = arrayOfNulls(ARRAY_SIZE_GRAPHING)
        private val dbAggregateArraySaving: Array<Packet?> = arrayOfNulls(ARRAY_SIZE_SAVING)
        private var currentIndexGraphing = 0
        private var currentIndexSaving = 0

        private val _totalProcessedPackets = AtomicInteger(0)
        val totalProcessedPackets: Int
            get() = _totalProcessedPackets.get()

        private val _previewDataFlow = MutableSharedFlow<Array<Packet>>(
            replay = 1,
            extraBufferCapacity = 100,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val previewDataFlow = _previewDataFlow.asSharedFlow()

        private val _storedDataFlow = MutableSharedFlow<Array<Packet>>(
            replay = 1,
            extraBufferCapacity = 100,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val storedDataFlow = _storedDataFlow.asSharedFlow()

        private val csvLock = ReentrantLock()

        private val csvLineBuilder = StringBuilder(256)

        fun aggregatePreviewData(packets: List<Packet>) {
            Log.d("DeviceDataAggregator", "Processing batch of ${packets.size} preview packets")

            val processedCount = min(packets.size, MAX_BATCH_SIZE)
            if (processedCount < packets.size) {
                Log.w("DeviceDataAggregator", "Preview batch size exceeded limit. Processing first $MAX_BATCH_SIZE packets.")
            }

            val processedPackets = packets.take(processedCount).map { packet ->
                processPacket(packet, isPreview = true)
            }.toTypedArray()

            _previewDataFlow.tryEmit(processedPackets)
        }

        fun aggregateStoredData(packets: List<Packet>) {
            Log.d("DeviceDataAggregator", "Processing batch of ${packets.size} stored packets")

            val processedCount = min(packets.size, MAX_BATCH_SIZE)
            if (processedCount < packets.size) {
                Log.w("DeviceDataAggregator", "Stored batch size exceeded limit. Processing first $MAX_BATCH_SIZE packets.")
            }

            val processedPackets = packets.take(processedCount).map { packet ->
                processPacket(packet, isPreview = false)
            }.toTypedArray()

            _storedDataFlow.tryEmit(processedPackets)
            _totalProcessedPackets.addAndGet(processedCount)
        }

        private fun processPacket(packet: Packet, isPreview: Boolean): Packet {
            if (isPreview) {
                dbAggregateArrayGraphing[currentIndexGraphing] = packet
                currentIndexGraphing = (currentIndexGraphing + 1) % ARRAY_SIZE_GRAPHING


                dbAggregateArraySaving[currentIndexSaving] = packet
                currentIndexSaving++

                if (currentIndexSaving >= ARRAY_SIZE_SAVING) {
                    saveToCSV(dbAggregateArraySaving, false)
                    currentIndexSaving = 0
                }
            }

            else {
                saveToCSV(dbAggregateArraySaving, true)
            }

            return packet
        }

        private fun saveToCSV(data: Array<Packet?>, isStoredData: Boolean) {

            var file: String = filename

            if (isStoredData) {
                file = historyFilename
            }

            else {
                file = filename
            }

            csvLock.withLock {
                val filePath = findOrCreateFile(file, data.firstOrNull(), metadataText)
                if (filePath != null) {
                    try {
                        FileWriter(filePath, true).use { writer ->
                            data.forEach { packet ->
                                if (packet != null) {
                                    writer.write(buildCsvLine(packet, isStoredData))
                                }
                            }
                        }
                    } catch (e: IOException) {
                        Log.e("DeviceDataAggregator", "Failed to write to CSV", e)
                    }
                } else {
                    Log.e("DeviceDataAggregator", "Failed to create or find CSV file")
                }
            }
        }

        private fun buildCsvLine(packet: Packet, isStoredData: Boolean): String {
            csvLineBuilder.clear()
            when (packet) {
                is ArgusPacket -> appendArgusCsvLine(csvLineBuilder, packet, isStoredData)
                is AurelianPacket -> appendAurelianCsvLine(csvLineBuilder, packet, isStoredData)
                is AeriePacket -> appendAerieCsvLine(csvLineBuilder, packet, isStoredData)
                else -> Log.w("DeviceDataAggregator", "Unknown packet type: ${packet::class.simpleName}")
            }
            return csvLineBuilder.toString()
        }

        private fun appendArgusCsvLine(sb: StringBuilder, packet: ArgusPacket, isStoredData: Boolean) {
            var csvIndex = 0

            if (isStoredData) {
                storedIndex++
                csvIndex = storedIndex
            }
            else {
                previewIndex++
                csvIndex = previewIndex
            }
            sb.append(csvIndex).append(';')
                .append(packet.captureTime).append(';')
                .append(packet.mm660_8).append(';')
                .append(packet.mm660_30).append(';')
                .append(packet.mm660_35).append(';')
                .append(packet.mm660_40).append(';')
                .append(packet.mm735_8).append(';')
                .append(packet.mm735_30).append(';')
                .append(packet.mm735_35).append(';')
                .append(packet.mm735_40).append(';')
                .append(packet.mm810_8).append(';')
                .append(packet.mm810_30).append(';')
                .append(packet.mm810_35).append(';')
                .append(packet.mm810_40).append(';')
                .append(packet.mm850_8).append(';')
                .append(packet.mm850_30).append(';')
                .append(packet.mm850_35).append(';')
                .append(packet.mm850_40).append(';')
                .append(packet.mm890_8).append(';')
                .append(packet.mm890_30).append(';')
                .append(packet.mm890_35).append(';')
                .append(packet.mm890_40).append(';')
                .append(packet.mmAmbient_8).append(';')
                .append(packet.mmAmbient_30).append(';')
                .append(packet.mmAmbient_35).append(';')
                .append(packet.mmAmbient_40).append(';')
                .append(packet.temperature).append(';')
                .append(packet.accelerometerX).append(';')
                .append(packet.accelerometerY).append(';')
                .append(packet.accelerometerZ).append(';')
                .append(packet.timer).append(';')
                .append(packet.sequenceCounter).append(';')
                .append(if (packet.eventBit) "TRUE" else "FALSE").append(';')
                .append(packet.hbO2).append(';')
                .append(packet.hbd).append(';')
                .append(packet.sessionId).append(';')
                .append(packet.pulseRate).append(';')
                .append(packet.respiratoryRate).append(';')
                .append(packet.spO2).append(';')
                .append(packet.ppgWaveform).append(';')
                .append(packet.stO2).append(';')
                .append(packet.reserved8).append(';')
                .append(packet.reserved16).append(';')
                .append(packet.reserved32).append('\n')
        }

        private fun appendAurelianCsvLine(sb: StringBuilder, packet: AurelianPacket, isStoredData: Boolean) {

            var csvIndex = 0

            if (isStoredData) {
                storedIndex++
                csvIndex = storedIndex
            }
            else {
                previewIndex++
                csvIndex = previewIndex
            }
            sb.append(csvIndex).append(';')
                .append(packet.captureTime).append(';')
                .append(packet.eegC1).append(';')
                .append(packet.eegC2).append(';')
                .append(packet.eegC3).append(';')
                .append(packet.eegC4).append(';')
                .append(packet.eegC5).append(';')
                .append(packet.eegC6).append(';')
                .append(packet.accelerometerX).append(';')
                .append(packet.accelerometerY).append(';')
                .append(packet.accelerometerZ).append(';')
                .append(packet.timeElapsed).append(';')
                .append(packet.counter).append(';')
                .append(if (packet.marker) "TRUE" else "FALSE").append(';')
                .append(packet.sessionId).append(';')
                .append(packet.pulseRate).append(';')
                .append(packet.tdcsImpedance).append(';')
                .append(packet.tdcsCurrent).append(';')
                .append(packet.tdcsOnTime).append(';')
                .append(packet.batteryRSOC).append(';')
                .append(packet.reserved8).append(';')
                .append(packet.reserved64).append('\n')
        }

        private fun appendAerieCsvLine(sb: StringBuilder, packet: AeriePacket, isStoredData: Boolean) {

            var csvIndex = 0

            if (isStoredData) {
                storedIndex++
                csvIndex = storedIndex
            }
            else {
                previewIndex++
                csvIndex = previewIndex
            }
            sb.append(csvIndex).append(';')
            .append(packet.captureTime).append(';')
                .append(packet.near740).append(';')
                .append(packet.near850).append(';')
                .append(packet.near940).append(';')
                .append(packet.mid740).append(';')
                .append(packet.mid850).append(';')
                .append(packet.mid940).append(';')
                .append(packet.far740).append(';')
                .append(packet.far850).append(';')
                .append(packet.far940).append(';')
                .append(packet.ambient).append(';')
                .append(packet.accelerometerX).append(';')
                .append(packet.accelerometerY).append(';')
                .append(packet.accelerometerZ).append(';')
                .append(packet.elapsedTime).append(';')
                .append(packet.timer).append(';')
                .append(if (packet.eventBit == 1) "TRUE" else "FALSE").append(';')
                .append(packet.hbO2).append(';')
                .append(packet.hbd).append(';')
                .append(packet.sessionId).append(';')
                .append(packet.pulseRate).append(';')
                .append(packet.respiratoryRate).append(';')
                .append(packet.spO2).append('\n')
        }

        private fun findOrCreateFile(filename: String, packet: Packet?, metadata: String): String? {
            val directory = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "NIRSense")
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e("DeviceDataAggregator", "Failed to create directory")
                return null
            }

            val sanitizedFilename = sanitizeFilename(filename)
            val file = File(directory, "$sanitizedFilename.csv")

            if (!file.exists()) {
                try {
                    if (!file.createNewFile()) {
                        Log.e("DeviceDataAggregator", "Failed to create new file")
                        return null
                    }
                    val header = getHeader(packet, metadata)
                    FileWriter(file).use { it.write(header) }
                } catch (e: IOException) {
                    Log.e("DeviceDataAggregator", "Error creating file", e)
                    return null
                }
            }

            return file.absolutePath
        }

        private fun getHeader(packet: Packet?, metadata: String): String {
            return when (packet) {
                is ArgusPacket -> getArgusHeader(metadata)
                is AurelianPacket -> getAurelianHeader(metadata)
                is AeriePacket -> getAerieHeader(metadata)
                null -> "Timestamp;Unknown Packet Type;$metadata\n"
                else -> "Timestamp;Unknown Packet Type (${packet::class.simpleName});$metadata\n"
            }
        }

        private fun getArgusHeader(metadata: String): String {
            return "Index;Capture Time;660-8mm;660-30mm;660-35mm;660-40mm;735-8mm;735-30mm;735-35mm;735-40mm;" +
                    "810-8mm;810-30mm;810-35mm;810-40mm;850-8mm;850-30mm;850-35mm;850-40mm;890-8mm;890-30mm;" +
                    "890-35mm;890-40mm;Ambient-8mm;Ambient-30mm;Ambient-35mm;Ambient-40mm;Temperature;AccelX;" +
                    "AccelY;AccelZ;Time_Elapsed;Counter;Marker;HBO2;HBD;Session;Pulse_Rate;Respiratory_Rate;" +
                    "SpO2;PPG;StO2;Reserved8;Reserved16;Reserved32;$metadata\n"
        }

        private fun getAurelianHeader(metadata: String): String {
            return "Index;Capture Time;EEG_C1;EEG_C2;EEG_C3;EEG_C4;EEG_C5;EEG_C6;AccelX;AccelY;AccelZ;" +
                    "Time_Elapsed;Counter;Marker;Session;Pulse_Rate;tDCS_Impedance;tDCS_Current;tDCS_On_Time;" +
                    "Battery_RSOC;Reserved8;Reserved64;$metadata\n"
        }

        //TODO FIX_ME AERIE_FIX ask if I should normalize the spelling of the header values to match the other csv outputs (for example, capture time)
        private fun getAerieHeader(metadata: String): String {
            return "Index;CaptureTime;Near770ADC;Near850ADC;Near940ADC;Mid770ADC;Mid850ADC;Mid940ADC;Far770ADC;Far850ADC;Far940ADC;AmbientADC;AccelerometerX;AccelerometerY;AccelerometerZ;ElapsedTime;TimerBits;EventBit;HbO2;Hbd;SessionId;PulseRate;RespiratoryRate;SpO2;$metadata\n"
        }

        private fun sanitizeFilename(filename: String): String {
            return filename.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                .take(255)
        }
    }
}

// Constants
const val ARGUS_1_TIME_DIVISOR = 125000.0
const val ARGUS_2_TIME_DIVISOR = 32768.0
const val ARGUS_MAX_TIMER_BITS = 32767u
const val ARGUS_DATA_PACKET_SIZE = 80
const val AERIE_DATA_PACKET_SIZE = 40