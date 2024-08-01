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

/**
 * Represents a connected Bluetooth device and manages its data and state.
 *
 * @property bluetoothGatt The BluetoothGatt instance for this device
 * @property filename The base filename for CSV data export
 * @property initialStatus The initial setup state of the device
 */
@SuppressLint("MissingPermission")
class Device(
    var bluetoothGatt: BluetoothGatt,
    var filename: String,
    initialStatus: BleManager.SetupState
) {
    // Device identification properties
    val macAddressString: String = bluetoothGatt.device.address
    val macAddress: Long = macAddressToLong(macAddressString)
    val name: String = bluetoothGatt.device.name ?: "Unknown Device"

    // Device version and family information
    var deviceVersionInfo: DeviceVersionInfo = DeviceVersionInfo("-1", 0, DeviceFamily.Unknown, 0)

    // Device state flags
    var deviceBusy = true
    var streamReady = false
    var hasCompletedSetupBefore: Boolean = false

    // Live data variables
    var index: Int = 1
    var captureTimePreview: Instant? = null
    var timerBitsPreview: UInt = 0u

    // Stored data variables
    var historyIndex: Int = 1
    var isStreamingStoredData: Boolean = false
    var historyFilename: String = "NIRSense"
    var captureTimeStored: Instant? = null
    var timerBitsStored: UInt = 0u

    // Battery level
    var battery: Int = -1

    // Connection status
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // Streaming status
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // Device setup status
    private val _status = MutableStateFlow(initialStatus)
    val status: StateFlow<BleManager.SetupState> = _status.asStateFlow()

    // Download status
    private val _isDownloadComplete = MutableStateFlow(false)
    val isDownloadComplete: StateFlow<Boolean> = _isDownloadComplete.asStateFlow()

    // Packet statistics
    private val _totalPackets = MutableStateFlow(0)
    val totalPackets: StateFlow<Int> = _totalPackets.asStateFlow()

    private val _currentPacketv2 = MutableStateFlow(0)
    val currentPacketv2: StateFlow<Int> = _currentPacketv2.asStateFlow()

    // Packet flow for real-time data streaming
    private val _packetFlow = MutableSharedFlow<Packet>(replay = 0, extraBufferCapacity = 64)
    val packetFlow = _packetFlow.asSharedFlow()

    // Data aggregator for processing and storing device data
    val dataAggregator = DeviceDataAggregator()

    /**
     * Calculates the current progress percentage of packet processing.
     */
    val progressPercent: Int
        get() = if (_totalPackets.value > 0) (_currentPacketv2.value * 100) / _totalPackets.value else 0

    /**
     * Generates metadata text for the device.
     */
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

    /**
     * Updates the connection status of the device.
     */
    fun setConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    /**
     * Updates the total number of packets to be processed.
     */
    fun setTotalPackets(value: Int) {
        _totalPackets.value = value
    }

    /**
     * Updates the current packet being processed.
     */
    fun setCurrentPacketv2(value: Int) {
        _currentPacketv2.value = value
    }

    /**
     * Updates the streaming status of the device.
     */
    fun setIsStreaming(value: Boolean) {
        _isStreaming.value = value
    }

    /**
     * Updates the setup status of the device.
     */
    fun setStatus(value: BleManager.SetupState) {
        _status.value = value
    }

    /**
     * Updates the download completion status.
     */
    fun setDownloadComplete(complete: Boolean) {
        _isDownloadComplete.value = complete
    }

    /**
     * Emits a new packet to the packet flow.
     */
    suspend fun emitPacket(packet: Packet) {
        _packetFlow.emit(packet)
    }

    /**
     * Generates a string with detailed device information.
     */
    fun getDeviceInfo(): String {
        return "Device Filename: $filename, MAC Address String: $macAddressString, " +
                "MAC Address: ${macAddress.toString(16).uppercase()}, " +
                "Firmware Version: ${deviceVersionInfo.firmwareVersion}, " +
                "Device Family: ${deviceVersionInfo.deviceFamily}, " +
                "Argus Version: ${deviceVersionInfo.argusVersion}"
    }

    /**
     * Represents the connection status of the device.
     */
    enum class ConnectionStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    /**
     * Holds version information for the device.
     */
    data class DeviceVersionInfo(
        var firmwareVersion: String,
        var nvmVersion: Int,
        var deviceFamily: DeviceFamily,
        var argusVersion: Int
    )

    /**
     * Represents the family of the device.
     */
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
        /**
         * Converts a MAC address string to a Long value.
         */
        fun macAddressToLong(macAddress: String): Long =
            macAddress.split(":")
                .map { it.toInt(16).toLong() }
                .reduce { acc, value -> (acc shl 8) or value }
    }

    /**
     * DeviceDataAggregator handles the aggregation, processing, and storage of packet data.
     */
    inner class DeviceDataAggregator {
        // Constants for array sizes and batch processing limits
        private val ARRAY_SIZE_GRAPHING = 6
        private val ARRAY_SIZE_SAVING = ARRAY_SIZE_GRAPHING * 100
        private val MAX_BATCH_SIZE = 1000

        // Pre-allocate arrays to avoid frequent allocations during processing
        private val dbAggregateArrayGraphing: Array<Packet?> = arrayOfNulls(ARRAY_SIZE_GRAPHING)
        private val dbAggregateArraySaving: Array<Packet?> = arrayOfNulls(ARRAY_SIZE_SAVING)
        private var currentIndexGraphing = 0
        private var currentIndexSaving = 0

        // Using AtomicInteger for thread-safe operations on packet count
        private val _totalProcessedPackets = AtomicInteger(0)
        val totalProcessedPackets: Int
            get() = _totalProcessedPackets.get()

        // MutableSharedFlow for emitting graphing data with a buffer to handle backpressure
        private val _graphingDataFlow = MutableSharedFlow<Array<Packet>>(
            replay = 1,
            extraBufferCapacity = 100,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val graphingDataFlow = _graphingDataFlow.asSharedFlow()

        // Use a lock for thread-safe CSV writing
        private val csvLock = ReentrantLock()

        // Reusable StringBuilder for CSV line building to reduce allocations
        private val csvLineBuilder = StringBuilder(256)

        /**
         * Aggregates and processes incoming packets.
         *
         * @param packets List of packets to process
         */
        fun aggregateData(packets: List<Packet>) {
            Log.d("DeviceDataAggregator", "Processing batch of ${packets.size} packets")

            // Limit batch size to prevent processing extremely large batches
            val processedCount = min(packets.size, MAX_BATCH_SIZE)
            if (processedCount < packets.size) {
                Log.w("DeviceDataAggregator", "Batch size exceeded limit. Processing first $MAX_BATCH_SIZE packets.")
            }

            packets.take(processedCount).forEach { packet ->
                processPacket(packet)
            }

            _totalProcessedPackets.addAndGet(processedCount)
        }

        /**
         * Processes a single packet, updating both graphing and saving buffers.
         */
        private fun processPacket(packet: Packet) {
            // Update graphing buffer
            dbAggregateArrayGraphing[currentIndexGraphing] = packet
            currentIndexGraphing++

            if (currentIndexGraphing == ARRAY_SIZE_GRAPHING) {
                emitGraphingData()
                currentIndexGraphing = 0
            }

            // Update saving buffer
            dbAggregateArraySaving[currentIndexSaving] = packet
            currentIndexSaving++

            if (currentIndexSaving >= ARRAY_SIZE_SAVING) {
                saveToCSV(dbAggregateArraySaving)
                currentIndexSaving = 0
            }
        }

        /**
         * Emits graphing data to the SharedFlow.
         */
        private fun emitGraphingData() {
            try {
                // Create a copy of the array to avoid mutation issues
                val graphingData = dbAggregateArrayGraphing.copyOf() as Array<Packet>
                _graphingDataFlow.tryEmit(graphingData)
            } catch (e: Exception) {
                Log.e("DeviceDataAggregator", "Failed to emit graphing data", e)
            }
        }

        /**
         * Saves the aggregated data to a CSV file.
         *
         * @param data Array of packets to save
         */
        private fun saveToCSV(data: Array<Packet?>) {
            csvLock.withLock { // Using a lock to ensure thread-safe file writing
                val filePath = findOrCreateFile(filename, data.firstOrNull(), metadataText)
                if (filePath != null) {
                    try {
                        FileWriter(filePath, true).use { writer ->
                            data.forEach { packet ->
                                if (packet != null) {
                                    writer.write(buildCsvLine(packet))
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

        /**
         * Builds a CSV line for a given packet.
         */
        private fun buildCsvLine(packet: Packet): String {
            csvLineBuilder.clear()
            when (packet) {
                is ArgusPacket -> appendArgusCsvLine(csvLineBuilder, packet)
                is AurelianPacket -> appendAurelianCsvLine(csvLineBuilder, packet)
                else -> Log.w("DeviceDataAggregator", "Unknown packet type: ${packet::class.simpleName}")
            }
            return csvLineBuilder.toString()
        }

        /**
         * Appends Argus packet data to the CSV line.
         */
        private fun appendArgusCsvLine(sb: StringBuilder, packet: ArgusPacket) {
            sb.append(packet.captureTime).append(';')
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

        /**
         * Appends Aurelian packet data to the CSV line.
         */
        private fun appendAurelianCsvLine(sb: StringBuilder, packet: AurelianPacket) {
            sb.append(packet.captureTime).append(';')
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

        /**
         * Finds or creates a file for storing the CSV data.
         *
         * @param filename The base filename to use
         * @param packet A sample packet to determine the header
         * @param metadata Additional metadata to include in the header
         * @return The path to the created or found file, or null if creation failed
         */
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

        /**
         * Generates the appropriate header for the CSV file based on the packet type.
         */
        private fun getHeader(packet: Packet?, metadata: String): String {
            return when (packet) {
                is ArgusPacket -> getArgusHeader(metadata)
                is AurelianPacket -> getAurelianHeader(metadata)
                null -> "Timestamp;Unknown Packet Type;$metadata\n"
                else -> "Timestamp;Unknown Packet Type (${packet::class.simpleName});$metadata\n"
            }
        }

        /**
         * Generates the header for Argus packet data.
         */
        private fun getArgusHeader(metadata: String): String {
            return "Index;Capture Time;660-8mm;660-30mm;660-35mm;660-40mm;735-8mm;735-30mm;735-35mm;735-40mm;" +
                    "810-8mm;810-30mm;810-35mm;810-40mm;850-8mm;850-30mm;850-35mm;850-40mm;890-8mm;890-30mm;" +
                    "890-35mm;890-40mm;Ambient-8mm;Ambient-30mm;Ambient-35mm;Ambient-40mm;Temperature;AccelX;" +
                    "AccelY;AccelZ;Time_Elapsed;Counter;Marker;HBO2;HBD;Session;Pulse_Rate;Respiratory_Rate;" +
                    "SpO2;PPG;StO2;Reserved8;Reserved16;Reserved32;$metadata\n"
        }

        /**
         * Generates the header for Aurelian packet data.
         */
        private fun getAurelianHeader(metadata: String): String {
            return "Index;Capture Time;EEG_C1;EEG_C2;EEG_C3;EEG_C4;EEG_C5;EEG_C6;AccelX;AccelY;AccelZ;" +
                    "Time_Elapsed;Counter;Marker;Session;Pulse_Rate;tDCS_Impedance;tDCS_Current;tDCS_On_Time;" +
                    "Battery_RSOC;Reserved8;Reserved64;$metadata\n"
        }

        /**
         * Sanitizes a CSV export's filename to prevent file I/O errors due to invalid characters.
         */
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