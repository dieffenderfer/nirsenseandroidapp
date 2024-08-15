package com.dieff.aurelian.foregroundService.data.manager

import android.annotation.SuppressLint
import android.util.Log
import com.dieff.aurelian.BuildConfig
import com.dieff.aurelian.foregroundService.ble.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Object responsible for parsing and processing data from NIRSense devices.
 * Handles both preview (live) data and stored historical data.
 */
object DataParser {
    private const val MAX_PACKETS_PER_PROCESS = 20 // Limit to avoid memory issues
    private const val AURELIAN_PACKET_SIZE = 120
    private const val AURELIAN_PACKETS_PER_MESSAGE = 5
    private const val ARGUS_DATA_PACKET_SIZE = 80
    private const val AERIE_PACKET_SIZE = 40 //TODO FIX_ME AERIE_FIX Verify
    private const val MAX_TIMER_BITS = 32767u
    private const val ARGUS_1_TIME_DIVISOR = 125000.0
    private const val ARGUS_2_TIME_DIVISOR = 32768.0

    private const val AERIE_TIME_DIVISOR = 125000.0
    private const val AURELIAN_TIME_DIVISOR = 32768.0

    private val PLACEHOLDER_TIMESTAMP = Instant.EPOCH
    private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS").withZone(ZoneOffset.UTC)

    // Special packet identifiers
    private val START_HISTORICAL = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
    private val END_HISTORICAL = byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)
    private val START_TIMESTAMP = byteArrayOf(0x01, 0x03, 0x05, 0x07, 0x09, 0x0b, 0x0d, 0x0f)

    private var initialHistoryCaptureTime: Instant? = null

    /**
     * Processes preview (live) data from a device.
     *
     * @param data The raw byte array of data from the device
     * @param device The Device object representing the connected device
     */
    @SuppressLint("SuspiciousIndentation")
    @Synchronized
    fun processPreviewData(data: ByteArray, device: Device) {
        if (BuildConfig.DEBUG_BLE) {
            Log.d("DataParser", "Entered processPreviewData")
        }

        val deviceVersionInfo = device.deviceVersionInfo
        val deviceMacAddress = device.macAddress

        Log.d("DataParser", "Processing preview data for device: $deviceMacAddress")

        val packets = when (deviceVersionInfo.deviceFamily) {
            Device.DeviceFamily.Argus -> processArgusData(data, device)
            Device.DeviceFamily.Aurelian -> processAurelianData(data, device)
            Device.DeviceFamily.Aerie -> processAerieData(data, device)
            else -> {
                Log.w("DataParser", "Unsupported device family: ${deviceVersionInfo.deviceFamily}")
                emptyList()
            }
        }

        when {
            packets.isEmpty() -> Log.d("DataParser", "No packets to process")
            packets.size <= MAX_PACKETS_PER_PROCESS -> {
                device.dataAggregator.aggregatePreviewData(packets)
                Log.d("DataParser", "Aggregated ${packets.size} preview data packets")
            }
            else -> Log.w("DataParser", "Packet count exceeds limit. Skipping ${packets.size} packets.")
        }

        if (BuildConfig.DEBUG_BLE) {
            Log.d("DataParser", "Exited processPreviewData")
        }
    }

    /**
     * Processes Aurelian device data.
     */
    private fun processAurelianData(data: ByteArray, device: Device): List<AurelianPacket> {
        if (data.size < AURELIAN_PACKET_SIZE) {
            Log.d("DataParser", "Aurelian data packet size too small. Expected: $AURELIAN_PACKET_SIZE, Actual: ${data.size}")
            return emptyList()
        }

        return parseAurelianSegment(data, device).map { packet ->
            packet.copy(captureTime = setCaptureTimePreviewData(packet, device))
        }
    }

    /**
     * Parses a segment of Aurelian data into a list of AurelianPacket objects.
     */
    private fun parseAurelianSegment(packetData: ByteArray, device: Device): List<AurelianPacket> {
        val buffer = ByteBuffer.wrap(packetData).order(ByteOrder.LITTLE_ENDIAN)

        val basePacket = AurelianPacket(
            deviceMacAddress = device.macAddress,
            captureTime = PLACEHOLDER_TIMESTAMP,
            accelerometerX = buffer.getShort(90),
            accelerometerY = buffer.getShort(92),
            accelerometerZ = buffer.getShort(94),
            timeElapsed = buffer.getInt(96).toDouble() / 5.0,
            counter = (buffer.getShort(100).toInt() and 0x7FFF) * 5,
            marker = (buffer.getShort(100).toInt() and 0x8000) != 0,
            sessionId = buffer.get(102).toUByte(),
            pulseRate = buffer.get(103).toUByte(),
            tdcsImpedance = buffer.getShort(104).toUShort(),
            tdcsCurrent = buffer.getShort(106).toUShort(),
            tdcsOnTime = buffer.getShort(108).toUShort(),
            batteryRSOC = buffer.get(110).toUByte(),
            reserved8 = buffer.get(111).toUByte(),
            reserved64 = buffer.getLong(112).toULong(),
            eegC1 = 0, eegC2 = 0, eegC3 = 0, eegC4 = 0, eegC5 = 0, eegC6 = 0
        )

        return (0 until AURELIAN_PACKETS_PER_MESSAGE).map { packetIndex ->
            basePacket.copy(
                counter = basePacket.counter + packetIndex,
                eegC1 = convertAdcData(packetData.slice(packetIndex * 18 + 0 until packetIndex * 18 + 3)),
                eegC2 = convertAdcData(packetData.slice(packetIndex * 18 + 3 until packetIndex * 18 + 6)),
                eegC3 = convertAdcData(packetData.slice(packetIndex * 18 + 6 until packetIndex * 18 + 9)),
                eegC4 = convertAdcData(packetData.slice(packetIndex * 18 + 9 until packetIndex * 18 + 12)),
                eegC5 = convertAdcData(packetData.slice(packetIndex * 18 + 12 until packetIndex * 18 + 15)),
                eegC6 = convertAdcData(packetData.slice(packetIndex * 18 + 15 until packetIndex * 18 + 18))
            )
        }
    }

    /**
     * Converts ADC data from a list of bytes to an Int.
     */
    private fun convertAdcData(data: List<Byte>): Int {
        val extended = ByteArray(4)
        data.toByteArray().copyInto(extended, 1, 0, 3)
        if (data[0].toInt() and 0x80 != 0) {
            extended[0] = 0xFF.toByte()
        }
        return ByteBuffer.wrap(extended).order(ByteOrder.BIG_ENDIAN).int
    }

    /**
     * Processes Argus device data.
     */
    private fun processArgusData(data: ByteArray, device: Device): List<ArgusPacket> {
        if (data.size < ARGUS_DATA_PACKET_SIZE) {
            Log.d("DataParser", "Argus data packet size too small")
            return emptyList()
        }

        val totalSegments = data.size / ARGUS_DATA_PACKET_SIZE
        Log.d("DataParser", "Processing Argus data with $totalSegments segments")

        return (0 until totalSegments).mapNotNull { segmentIndex ->
            val segment = data.copyOfRange(
                segmentIndex * ARGUS_DATA_PACKET_SIZE,
                (segmentIndex + 1) * ARGUS_DATA_PACKET_SIZE
            )

            parseArgusSegment(segment, device).also { packet ->
                packet.captureTime = setCaptureTimePreviewData(packet, device)
                Log.d("DataParser", "Processed Argus data segment $segmentIndex")
            }
        }
    }

    /**
     * Parses a segment of Argus data into an ArgusPacket object.
     */
    private fun parseArgusSegment(segment: ByteArray, device: Device): ArgusPacket {
        val buffer = ByteBuffer.wrap(segment).order(ByteOrder.LITTLE_ENDIAN)
        Log.d("DataParser", "Parsing Argus segment")

        return ArgusPacket(
            deviceMacAddress = device.macAddress,
            argusVersion = device.deviceVersionInfo.argusVersion,
            captureTime = PLACEHOLDER_TIMESTAMP,
            mm660_8 = buffer.getShort(0),
            mm660_30 = buffer.getShort(2),
            mm660_35 = buffer.getShort(4),
            mm660_40 = buffer.getShort(6),
            mm735_8 = buffer.getShort(8),
            mm735_30 = buffer.getShort(10),
            mm735_35 = buffer.getShort(12),
            mm735_40 = buffer.getShort(14),
            mm810_8 = buffer.getShort(16),
            mm810_30 = buffer.getShort(18),
            mm810_35 = buffer.getShort(20),
            mm810_40 = buffer.getShort(22),
            mm850_8 = buffer.getShort(24),
            mm850_30 = buffer.getShort(26),
            mm850_35 = buffer.getShort(28),
            mm850_40 = buffer.getShort(30),
            mm890_8 = buffer.getShort(32),
            mm890_30 = buffer.getShort(34),
            mm890_35 = buffer.getShort(36),
            mm890_40 = buffer.getShort(38),
            mmAmbient_8 = buffer.getShort(40),
            mmAmbient_30 = buffer.getShort(42),
            mmAmbient_35 = buffer.getShort(44),
            mmAmbient_40 = buffer.getShort(46),
            temperature = buffer.getShort(48) / 100f,
            accelerometerX = buffer.getShort(50),
            accelerometerY = buffer.getShort(52),
            accelerometerZ = buffer.getShort(54),
            timer = buffer.getInt(56).toUInt(),
            sequenceCounter = (((buffer.get(61).toUShort().toInt() shl 8) + buffer.get(60).toUShort().toInt()) and 0x7FFF).toUShort(),
            eventBit = (buffer.get(61).toInt() and 0x80) != 0,
            hbO2 = HalfPrecisionConverter.toFloat(buffer.getShort(62).toInt()),
            hbd = HalfPrecisionConverter.toFloat(buffer.getShort(64).toInt()),
            sessionId = buffer.get(66).toUByte(),
            pulseRate = buffer.get(67).toUByte(),
            respiratoryRate = buffer.get(68).toUByte(),
            spO2 = buffer.get(69).toUByte(),
            ppgWaveform = buffer.getShort(70),
            stO2 = buffer.get(72).toUByte(),
            reserved8 = buffer.get(73).toUByte(),
            reserved16 = buffer.getShort(74).toUShort(),
            reserved32 = buffer.getInt(76).toUInt()
        )
    }

    /**
     * Processes Aerie device data.
     */
    private fun processAerieData(data: ByteArray, device: Device): List<AeriePacket> {
        if (data.size < AERIE_PACKET_SIZE) {
            Log.d("DataParser", "Aerie data packet size too small. Expected: $AERIE_PACKET_SIZE, Actual: ${data.size}")
            return emptyList()
        }

        return parseAerieSegment(data, device).map { packet ->
            packet.copy(captureTime = setCaptureTimePreviewData(packet, device))
        }
    }

    /**
     * Parses a segment of Aerie data into a list of AeriePacket objects.
     */
    private fun parseAerieSegment(packetData: ByteArray, device: Device): List<AeriePacket> {
        val buffer = ByteBuffer.wrap(packetData)

        // Big-endian for the first part
        buffer.order(ByteOrder.BIG_ENDIAN)
        val near740 = buffer.getShort(0).toInt() and 0xFFFF
        val near850 = buffer.getShort(2).toInt() and 0xFFFF
        val near940 = buffer.getShort(4).toInt() and 0xFFFF
        val mid740 = buffer.getShort(6).toInt() and 0xFFFF
        val mid850 = buffer.getShort(8).toInt() and 0xFFFF
        val mid940 = buffer.getShort(10).toInt() and 0xFFFF
        val far740 = buffer.getShort(12).toInt() and 0xFFFF
        val far850 = buffer.getShort(14).toInt() and 0xFFFF
        val far940 = buffer.getShort(16).toInt() and 0xFFFF
        val ambient = buffer.getShort(18).toInt() and 0xFFFF

        // Little-endian for the rest
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val accx = buffer.getShort(20).toInt()
        val accy = buffer.getShort(22).toInt()
        val accz = buffer.getShort(24).toInt()
        val elapsedTime = buffer.getInt(26)
        val timer = buffer.getShort(30).toLong() and 0xFFFF
        val hbO2 = HalfPrecisionConverter.toFloat(buffer.getShort(32).toInt())
        val hbd = HalfPrecisionConverter.toFloat(buffer.getShort(34).toInt())
        val counter = buffer.getShort(30).toInt() and 0x7FFF
        val eventBit = (packetData[31].toInt() shr 7) and 0x01
        val sessionId = packetData[36].toUByte()
        val pulseRate = packetData[37].toInt() and 0xFF
        val respiratoryRate = packetData[38].toInt() and 0xFF
        val spO2 = packetData[39].toInt() and 0xFF
        val ppg = near740 //TODO FIX_ME AERIE_FIX verify

        val basePacket = AeriePacket(
            deviceMacAddress = device.macAddress,
            captureTime = PLACEHOLDER_TIMESTAMP,
            near740 = near740,
            near850 = near850,
            near940 = near940,
            mid740 = mid740,
            mid850 = mid850,
            mid940 = mid940,
            far740 = far740,
            far850 = far850,
            far940 = far940,
            ambient = ambient,
            accelerometerX = accx,
            accelerometerY = accy,
            accelerometerZ = accz,
            elapsedTime = elapsedTime.toLong(),
            timer = timer,
            hbO2 = hbO2,
            hbd = hbd,
            counter = counter,
            eventBit = eventBit,
            sessionId = sessionId,
            pulseRate = pulseRate,
            respiratoryRate = respiratoryRate,
            ppg = ppg,
            spO2 = spO2,
            patchId = device.macAddress,
            captureTimePreview = device.captureTimePreview?.toEpochMilli()
        )

        return listOf(basePacket)
    }

    /**
     * Sets the capture time for preview data packets.
     */
    private fun setCaptureTimePreviewData(packet: Packet, device: Device): Instant {
        val currentTimerBits = when (packet) {
            is ArgusPacket -> packet.sequenceCounter.toUInt()
            is AurelianPacket -> packet.counter.toUInt()
            is AeriePacket -> packet.counter.toUInt()
            else -> return Instant.now()
        }

        val previousTimerBits = device.timerBitsPreview

        val timeMultiplier = if (currentTimerBits < previousTimerBits) {
            ((MAX_TIMER_BITS + 1U) - previousTimerBits) + currentTimerBits
        } else {
            currentTimerBits - previousTimerBits
        }

        val captureTime = if (timeMultiplier == 1U) {
            val elapsedTime = when (packet) {
                is ArgusPacket -> packet.timer.toDouble() / if (packet.argusVersion == 2) ARGUS_2_TIME_DIVISOR else ARGUS_1_TIME_DIVISOR
                is AurelianPacket -> packet.timeElapsed / AURELIAN_TIME_DIVISOR
                is AeriePacket -> packet.elapsedTime.toDouble() / AERIE_TIME_DIVISOR
                else -> 0.0
            }
            val nano = (elapsedTime * 1_000_000).toLong()
            device.captureTimePreview?.plusNanos(nano) ?: Instant.now()
        } else {
            Instant.now()
        }

        device.captureTimePreview = captureTime
        device.timerBitsPreview = currentTimerBits

        return captureTime
    }

    /**
     * Processes stored historical data from a device.
     */
    @Synchronized
    fun processStoredData(data: ByteArray, device: Device) {
        Log.d("DataParser", "Processing stored data")

        when (device.deviceVersionInfo.deviceFamily) {
            Device.DeviceFamily.Argus -> processArgusStoredData(data, device)
            Device.DeviceFamily.Aurelian -> processAurelianStoredData(data, device)
            Device.DeviceFamily.Aerie -> processAerieStoredData(data, device)
            else -> Log.w("DataParser", "Unsupported device family for stored data")
        }
    }

    /**
     * Processes stored data from an Argus device.
     */
    private fun processArgusStoredData(data: ByteArray, device: Device) {
        for (i in data.indices step ARGUS_DATA_PACKET_SIZE) {
            if (data.size >= i + ARGUS_DATA_PACKET_SIZE) {
                val singleStorage = data.sliceArray(i until i + ARGUS_DATA_PACKET_SIZE)
                processArgusStorageSegment(singleStorage, device)
            }
        }
    }

    /**
     * Processes stored data from an Aurelian device.
     */
    private fun processAurelianStoredData(data: ByteArray, device: Device) {
        for (i in data.indices step AURELIAN_PACKET_SIZE) {
            if (data.size >= i + AURELIAN_PACKET_SIZE) {
                val singleStorage = data.sliceArray(i until i + AURELIAN_PACKET_SIZE)
                processAurelianStorageSegment(singleStorage, device)
            }
        }
    }

    /**
     * Processes stored data from an Aerie device.
     */
    private fun processAerieStoredData(data: ByteArray, device: Device) {
        for (i in data.indices step AERIE_PACKET_SIZE) {
            if (data.size >= i + AERIE_PACKET_SIZE) {
                val singleStorage = data.sliceArray(i until i + AERIE_PACKET_SIZE)
                processAerieStorageSegment(singleStorage, device)
            }
        }
    }

    /**
     * Processes a single segment of stored Argus data.
     */
    private fun processArgusStorageSegment(singleStorage: ByteArray, device: Device) {
        val singleComp = singleStorage.take(8).toByteArray()

        when {
            singleComp.contentEquals(END_HISTORICAL) -> {
                Log.d("DataParser", "End packet received")
                onTotalDownloadComplete(device)
            }
            singleComp.contentEquals(START_HISTORICAL) -> handleStartHistorical(singleStorage, device)
            singleComp.contentEquals(START_TIMESTAMP) -> handleStartTimeStamp(singleStorage)
            else -> processArgusDataSegment(singleStorage, device)
        }
    }

    /**
     * Processes a single segment of stored Aurelian data.
     */
    private fun processAurelianStorageSegment(singleStorage: ByteArray, device: Device) {
        val singleComp = singleStorage.take(8).toByteArray()

        when {
            singleComp.contentEquals(END_HISTORICAL) -> {
                Log.d("DataParser", "End packet received")
                onTotalDownloadComplete(device)
            }
            singleComp.contentEquals(START_HISTORICAL) -> handleStartHistorical(singleStorage, device)
            singleComp.contentEquals(START_TIMESTAMP) -> handleStartTimeStamp(singleStorage)
            else -> processAurelianDataSegment(singleStorage, device)
        }
    }

    /**
     * Processes a single segment of stored Aerie data.
     */
    private fun processAerieStorageSegment(singleStorage: ByteArray, device: Device) {
        val singleComp = singleStorage.take(8).toByteArray()

        when {
            singleComp.contentEquals(END_HISTORICAL) -> {
                Log.d("DataParser", "End packet received")
                onTotalDownloadComplete(device)
            }
            singleComp.contentEquals(START_HISTORICAL) -> handleStartHistorical(singleStorage, device)
            singleComp.contentEquals(START_TIMESTAMP) -> handleStartTimeStamp(singleStorage)
            else -> processAerieDataSegment(singleStorage, device)
        }
    }

    /**
     * Handles the start of historical data transmission.
     */
    private fun handleStartHistorical(singleStorage: ByteArray, device: Device) {
        Log.d("DataParser", "Start packet received")
        device.captureTimeStored = null
        device.timerBitsStored = 0u
        device.historyIndex = 1

        val packetCount = ByteBuffer.wrap(singleStorage.sliceArray(8 until 12))
            .order(ByteOrder.LITTLE_ENDIAN).int

        val devicePacketCountMultiplier = when (device.deviceVersionInfo.deviceFamily) {
            Device.DeviceFamily.Argus -> if (device.deviceVersionInfo.argusVersion >= 2) 1 else 1
            Device.DeviceFamily.Aurelian -> AURELIAN_PACKETS_PER_MESSAGE
            Device.DeviceFamily.Aerie -> 1
            else -> 1
        }

        val totalPackets = packetCount * devicePacketCountMultiplier

        if (totalPackets == 0) {
            Log.d("DataParser", "No historical data")
            onTotalDownloadComplete(device)
            return
        }

        device.isStreamingStoredData = true
        device.setTotalPackets(totalPackets)
        device.setCurrentPacketv2(0)
    }

    /**
     * Handles the start timestamp packet for historical data.
     */
    private fun handleStartTimeStamp(singleStorage: ByteArray) {
        Log.d("DataParser", "Start timestamp packet received")
        val timestamp = ByteBuffer.wrap(singleStorage.sliceArray(8 until 16))
            .order(ByteOrder.LITTLE_ENDIAN).long
        initialHistoryCaptureTime = Instant.ofEpochMilli(timestamp)
        Log.d("DataParser", "Initial history capture time: $initialHistoryCaptureTime")
    }

    /**
     * Processes a single Argus data segment from stored data.
     */
    private fun processArgusDataSegment(singleStorage: ByteArray, device: Device) {
        val historyPacketData = parseArgusSegment(singleStorage, device)
        processHistoryPacketData(historyPacketData, device)
    }

    /**
     * Processes a single Aurelian data segment from stored data.
     */
    private fun processAurelianDataSegment(singleStorage: ByteArray, device: Device) {
        val historyPacketDataList = parseAurelianSegment(singleStorage, device)
        historyPacketDataList.forEach { historyPacketData ->
            processHistoryPacketData(historyPacketData, device)
        }
    }

    /**
     * Processes a single Aerie data segment from stored data.
     */
    private fun processAerieDataSegment(singleStorage: ByteArray, device: Device) {
        val historyPacketData = parseAerieSegment(singleStorage, device).first()
        processHistoryPacketData(historyPacketData, device)
    }

    /**
     * Processes a single history packet data.
     */
    private fun processHistoryPacketData(historyPacketData: Packet, device: Device) {
        val captureTime = setCaptureTimeStoredData(historyPacketData, device)
        historyPacketData.captureTime = captureTime

        device.dataAggregator.aggregateStoredData(listOf(historyPacketData))

        device.historyIndex++
        device.setCurrentPacketv2(device.currentPacketv2.value + 1)
    }

    /**
     * Sets the capture time for stored data packets.
     */
    private fun setCaptureTimeStoredData(packet: Packet, device: Device): Instant {
        val currentTimerBits = when (packet) {
            is ArgusPacket -> packet.sequenceCounter.toUInt()
            is AurelianPacket -> packet.counter.toUInt()
            is AeriePacket -> packet.counter.toUInt()
            else -> return initialHistoryCaptureTime ?: Instant.now() // Fallback if packet type is unknown
        }

        val previousTimerBits = device.timerBitsStored ?: currentTimerBits

//        var timeMultiplier = if (currentTimerBits < previousTimerBits) {
//            ((MAX_TIMER_BITS + 1U) - previousTimerBits) + currentTimerBits
//        } else {
//            currentTimerBits - previousTimerBits
//        }

        // This is a temporary measure, but
        // We aren't really anticipating dropped packets for stored data transfer //TODO FIX_ME
        var timeMultiplier = 1U

        val captureTime = if (timeMultiplier == 1U) { // If no dropped packets
            val elapsedTime = when (packet) {
                is ArgusPacket -> packet.timer.toDouble() / if (packet.argusVersion == 2) ARGUS_2_TIME_DIVISOR else ARGUS_1_TIME_DIVISOR
                is AurelianPacket -> (packet.timeElapsed / AURELIAN_TIME_DIVISOR)
                is AeriePacket -> (packet.elapsedTime.toDouble() / AERIE_TIME_DIVISOR)
                else -> 0.0
            }
            val realms = (elapsedTime).toDouble()
            val realmsdiv4 = (realms)
            val realmsdivnano = (realmsdiv4  * 1000000000).toLong()
            device.captureTimeStored?.plusNanos(realmsdivnano) ?: initialHistoryCaptureTime ?: Instant.now() //Instant.now in case of emergency, in case we never received an initial timestamp and need something to work with
        } else {
            Log.w("DataParser", "Unexpected time multiplier for stored data: $timeMultiplier")
            device.captureTimeStored ?: initialHistoryCaptureTime ?: Instant.now()
        }

        // Update device with new capture time and timer bits
        device.captureTimeStored = captureTime
        device.timerBitsStored = currentTimerBits

        return captureTime
    }

    /**
     * Handles the completion of the total download process.
     */
    private fun onTotalDownloadComplete(device: Device) {
        Log.d("DataParser", "Total download complete")
        device.isStreamingStoredData = false
        device.historyIndex = 1
        device.setDownloadComplete(true)
    }
}