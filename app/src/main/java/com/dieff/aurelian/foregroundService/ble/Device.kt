package com.dieff.aurelian.foregroundService.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.util.Log
import com.dieff.aurelian.AppConfig.appVersion
import com.dieff.aurelian.globalAppContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.TimeZone

@SuppressLint("MissingPermission")
class Device(
    var bluetoothGatt: BluetoothGatt,
    var filename: String,
    initialStatus: BleManager.SetupState
) {
    val macAddressString: String = bluetoothGatt.device.address
    val macAddress: Long = macAddressToLong(macAddressString)
    val name: String = bluetoothGatt.device.name

    var deviceVersionInfo: DeviceVersionInfo = DeviceVersionInfo("-1", 0, DeviceFamily.Unknown, 0)

    var deviceBusy = true
    var streamReady = false

    var index: Int = 1
    var captureTimePreview: Instant? = null
    var timerBitsPreview: UInt = 0u

    var historyIndex: Int = 1
    var isStreamingStoredData: Boolean = false
    var historyFilename: String = "NIRSense"
    var captureTimeStored: Instant? = null
    var timerBitsStored: UInt = 0u

    var battery: Int = -1

    var hasCompletedSetupBefore: Boolean = false

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    fun setConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    enum class ConnectionStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    private val _totalPackets = MutableStateFlow(0)
    val totalPackets: StateFlow<Int> = _totalPackets.asStateFlow()

    private val _currentPacket = MutableStateFlow(0)
    val currentPacket: StateFlow<Int> = _currentPacket.asStateFlow()

    private val _currentPacketv2 = MutableStateFlow(0)
    val currentPacketv2: StateFlow<Int> = _currentPacketv2.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _status = MutableStateFlow(initialStatus)
    var status: StateFlow<BleManager.SetupState> = _status.asStateFlow()

    val progressPercent: Int
        get() = if (_totalPackets.value > 0) (_currentPacket.value * 100) / _totalPackets.value else 0

    fun setTotalPackets(value: Int) {
        _totalPackets.value = value
    }

    fun setCurrentPacket(value: Int) {
        _currentPacket.value = value
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

    private val _isDownloadComplete = MutableStateFlow(false)
    val isDownloadComplete: StateFlow<Boolean> = _isDownloadComplete.asStateFlow()

    fun setDownloadComplete(complete: Boolean) {
        _isDownloadComplete.value = complete
    }

    private val _packetFlow = MutableSharedFlow<Packet>(replay = 0, extraBufferCapacity = 64)
    val packetFlow = _packetFlow.asSharedFlow()

    suspend fun emitPacket(packet: Packet) {
        _packetFlow.emit(packet)
    }

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

    fun getDeviceInfo(): String {
        return "Device Filename: $filename, MAC Address String: $macAddressString, MAC Address: ${macAddress.toString(16).uppercase()}, Firmware Version: ${deviceVersionInfo.firmwareVersion}, Device Family: ${deviceVersionInfo.deviceFamily}, Argus Version: ${deviceVersionInfo.argusVersion}"
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
            fun fromInt(value: Int): DeviceFamily {
                return values().find { it.value == value } ?: Unknown
            }
        }
    }

    companion object {
        fun macAddressToLong(macAddress: String): Long {
            return macAddress.split(":")
                .map { it.toInt(16).toLong() }
                .reduce { acc, value -> (acc shl 8) or value }
        }
    }

    val dataAggregator = DeviceDataAggregator()

    inner class DeviceDataAggregator {
        private val ARRAY_SIZE_GRAPHING = 6 //TODO: set this and other ones dynamically
        private val ARRAY_SIZE_SAVING = ARRAY_SIZE_GRAPHING * 100

        private val MAX_PACKETS_PER_AGGREGATE = 20

        private var dbAggregateArrayGraphing: Array<Packet?> = arrayOfNulls(ARRAY_SIZE_GRAPHING)
        private var dbAggregateArraySaving: Array<Packet?> = arrayOfNulls(ARRAY_SIZE_SAVING)
        private var currentIndexGraphing = 0
        private var currentIndexSaving = 0

        private val _graphingDataFlow = MutableSharedFlow<Array<Packet?>>(replay = 1, extraBufferCapacity = 1)
        val graphingDataFlow = _graphingDataFlow.asSharedFlow()

        suspend fun aggregateData(packets: List<Packet>) {

            Log.d("DBG", "DeviceDataAggregator aggregateData packets.size = ${packets.size}")

            if (packets.size > MAX_PACKETS_PER_AGGREGATE) {
                Log.w("DBG", "Packet count exceeds limit. Skipping ${packets.size} packets.")
                return
            }

            packets.forEach { packet ->
                // Graphing
                if (currentIndexGraphing < ARRAY_SIZE_GRAPHING) {
                    dbAggregateArrayGraphing[currentIndexGraphing] = packet
                    currentIndexGraphing++
                }

                if (currentIndexGraphing == ARRAY_SIZE_GRAPHING) {
                    _graphingDataFlow.emit(dbAggregateArrayGraphing)
                    currentIndexGraphing = 0
                }

                // Saving to db
                if (currentIndexSaving < ARRAY_SIZE_SAVING) {
                    dbAggregateArraySaving[currentIndexSaving] = packet
                    currentIndexSaving++
                }

                if (currentIndexSaving >= ARRAY_SIZE_SAVING) {
                    saveToCSV(dbAggregateArraySaving)
                    currentIndexSaving = 0
                }
            }
        }

        private fun saveToCSV(data: Array<Packet?>) {
            val firstPacket = data.firstOrNull() ?: return
            val filePath = findOrCreateFile(filename, firstPacket, metadataText)
            val saveString = StringBuilder()

            for (datum in data) {
                saveString.append(buildCsvLine(index, datum))
                index += 1
            }

            if (filePath != null) {
                appendStringToCSV(filePath, saveString.toString())
            }
        }

        private fun findOrCreateFile(filename: String, packet: Packet, metadata: String): String? {
            val directory = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "NIRSense")
            if (!directory.exists()) {
                directory.mkdirs()
            }

            val sanitizedFilename = sanitizeFilename(filename)
            val myFile = File(directory, "$sanitizedFilename.csv")
            if (!myFile.exists()) {
                myFile.createNewFile()
                val header = getHeader(packet, metadata)
                myFile.writeText(header)
            }
            return myFile.absolutePath
        }

        private fun getHeader(packet: Packet, metadata: String): String {
            return when (packet) {
                is ArgusPacket -> getArgusHeader(metadata)
                is AurelianPacket -> getAurelianHeader(metadata)
                else -> throw IllegalArgumentException("Unknown packet type")
            }
        }

        private fun buildCsvLine(index: Int, packet: Packet?): String {
            return when (packet) {
                is ArgusPacket -> buildArgusCsvLine(index, packet)
                is AurelianPacket -> buildAurelianCsvLine(index, packet)
                else -> ""
            }
        }

        private fun buildArgusCsvLine(index: Int, packet: ArgusPacket): String {
            return "$index;${packet.captureTime};${packet.mm660_8};${packet.mm660_30};${packet.mm660_35};${packet.mm660_40};" +
                    "${packet.mm735_8};${packet.mm735_30};${packet.mm735_35};${packet.mm735_40};${packet.mm810_8};${packet.mm810_30};" +
                    "${packet.mm810_35};${packet.mm810_40};${packet.mm850_8};${packet.mm850_30};${packet.mm850_35};${packet.mm850_40};" +
                    "${packet.mm890_8};${packet.mm890_30};${packet.mm890_35};${packet.mm890_40};${packet.mmAmbient_8};${packet.mmAmbient_30};" +
                    "${packet.mmAmbient_35};${packet.mmAmbient_40};${packet.temperature};${packet.accelerometerX};${packet.accelerometerY};" +
                    "${packet.accelerometerZ};${packet.timer};${packet.sequenceCounter};${if (packet.eventBit) "TRUE" else "FALSE"};${packet.hbO2};" +
                    "${packet.hbd};${packet.sessionId};${packet.pulseRate};${packet.respiratoryRate};${packet.spO2};${packet.ppgWaveform};" +
                    "${packet.stO2};${packet.reserved8};${packet.reserved16};${packet.reserved32}\n"
        }

        private fun buildAurelianCsvLine(index: Int, packet: AurelianPacket): String {
            return "$index;${packet.captureTime};${packet.eegC1};${packet.eegC2};${packet.eegC3};${packet.eegC4};${packet.eegC5};${packet.eegC6};" +
                    "${packet.accelerometerX};${packet.accelerometerY};${packet.accelerometerZ};${packet.timeElapsed};${packet.counter};" +
                    "${if (packet.marker) "TRUE" else "FALSE"};${packet.sessionId};${packet.pulseRate};${packet.tdcsImpedance};${packet.tdcsCurrent};" +
                    "${packet.tdcsOnTime};${packet.batteryRSOC};${packet.reserved8};${packet.reserved64}\n"
        }

        private fun getArgusHeader(metadata: String): String {
            return "Index;Capture Time;660-8mm;660-30mm;660-35mm;660-40mm;735-8mm;735-30mm;735-35mm;735-40mm;810-8mm;810-30mm;810-35mm;810-40mm;850-8mm;850-30mm;850-35mm;850-40mm;890-8mm;890-30mm;890-35mm;890-40mm;Ambient-8mm;Ambient-30mm;Ambient-35mm;Ambient-40mm;Temperature;AccelX;AccelY;AccelZ;Time_Elapsed;Counter;Marker;HBO2;HBD;Session;Pulse_Rate;Respiratory_Rate;SpO2;PPG;StO2;Reserved8;Reserved16;Reserved32;$metadata\n"
        }

        private fun getAurelianHeader(metadata: String): String {
            return "Index;Capture Time;EEG_C1;EEG_C2;EEG_C3;EEG_C4;EEG_C5;EEG_C6;AccelX;AccelY;AccelZ;Time_Elapsed;Counter;Marker;Session;Pulse_Rate;tDCS_Impedance;tDCS_Current;tDCS_On_Time;Battery_RSOC;Reserved8;Reserved64;$metadata\n"
        }

        private fun appendStringToCSV(filePath: String, data: String) {
            try {
                FileWriter(filePath, true).use { it.write(data) }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        private fun sanitizeFilename(filename: String): String {
            val illegalCharacters = "[/\\\\:*?\"<> |]".toRegex()
            var sanitizedFilename = filename.replace(illegalCharacters, "_")
            sanitizedFilename = sanitizedFilename.trim().trimStart('.').trimEnd('.')
            if (sanitizedFilename.length > 255) {
                sanitizedFilename = sanitizedFilename.substring(0, 255)
            }
            return sanitizedFilename
        }
    }
}

// Constants
const val ARGUS_1_TIME_DIVISOR = 125000.0
const val ARGUS_2_TIME_DIVISOR = 32768.0
const val ARGUS_MAX_TIMER_BITS = 32767u
const val ARGUS_DATA_PACKET_SIZE = 80

val globalReadingDateFormat: SimpleDateFormat by lazy {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS")
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    sdf
}