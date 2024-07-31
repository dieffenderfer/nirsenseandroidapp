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

object DataParser {

    private const val MAX_PACKETS_PER_PROCESS = 20 //limit to avoid memory issues

    private const val AURELIAN_PACKET_SIZE = 120
    private const val AURELIAN_PACKETS_PER_MESSAGE = 5

    private val PLACEHOLDER_TIMESTAMP = Instant.EPOCH

    @SuppressLint("SuspiciousIndentation")
    @Synchronized
    suspend fun processPreviewData(data: ByteArray, device: Device) {
        if (BuildConfig.DEBUG_BLE) {
            Log.d("DBG", "DataParser - Entered processData")
        }

        val deviceVersionInfo = device.deviceVersionInfo
        val deviceMacAddress = device.macAddress

        Log.d("DBG", "Processing preview data for device: $deviceMacAddress")

        val packets = when (deviceVersionInfo.deviceFamily) {
            Device.DeviceFamily.Argus -> processArgusData(data, device)
            Device.DeviceFamily.Aurelian -> processAurelianData(data, device)
            else -> emptyList()
        }

        if (packets.isNotEmpty() && packets.size <= MAX_PACKETS_PER_PROCESS) {
            device.dataAggregator.aggregateData(packets)
            Log.d("DBG", "Aggregated ${packets.size} preview data packets")
        } else if (packets.size > MAX_PACKETS_PER_PROCESS) {
            Log.w("DBG", "Packet count exceeds limit. Skipping ${packets.size} packets.")
        } else {
            Log.d("DBG", "No packets to process")
        }

        if (BuildConfig.DEBUG_BLE) {
            Log.d("DBG", "DataParser - Exited processData")
        }
    }

    private fun processAurelianData(data: ByteArray, device: Device): List<AurelianPacket> {
        if (data.size < AURELIAN_PACKET_SIZE) {
            Log.d("DBG", "Aurelian data packet size too small. Expected: $AURELIAN_PACKET_SIZE, Actual: ${data.size}")
            return emptyList()
        }

        val packets = parseAurelianPacket(data, device)

        return packets.map { packet ->
            packet.copy(captureTime = setCaptureTimePreviewData(packet, device))
        }
    }

    private fun parseAurelianPacket(packetData: ByteArray, device: Device): List<AurelianPacket> {
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

    private fun convertAdcData(data: List<Byte>): Int {
        val extended = ByteArray(4)
        data.toByteArray().copyInto(extended, 1, 0, 3)
        if (data[0].toInt() and 0x80 != 0) {
            extended[0] = 0xFF.toByte()
        }
        return ByteBuffer.wrap(extended).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun processArgusData(data: ByteArray, device: Device): List<ArgusPacket> {
        if (data.size < ARGUS_DATA_PACKET_SIZE) {
            Log.d("DBG", "Argus data packet size too small")
            return emptyList()
        }

        val totalSegments = data.size / ARGUS_DATA_PACKET_SIZE
        Log.d("DBG", "Processing Argus data with $totalSegments segments")

        val packets = mutableListOf<ArgusPacket>()

        for (segmentIndex in 0 until totalSegments) {
            val segment = data.copyOfRange(
                segmentIndex * ARGUS_DATA_PACKET_SIZE,
                (segmentIndex + 1) * ARGUS_DATA_PACKET_SIZE
            )

            val packet = parseArgusSegment(segment, device)
            packet.captureTime = setCaptureTimePreviewData(packet, device)
            packets.add(packet)
            Log.d("DBG", "Processed Argus data segment $segmentIndex")
        }

        return packets
    }

    private fun parseArgusSegment(segment: ByteArray, device: Device): ArgusPacket {
        val buffer = ByteBuffer.wrap(segment).order(ByteOrder.LITTLE_ENDIAN)
        Log.d("DBG", "Parsing Argus segment")

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

    private fun setCaptureTimePreviewData(packet: Packet, device: Device): Instant {
        val currentTimerBits = when (packet) {
            is ArgusPacket -> packet.sequenceCounter.toUInt()
            is AurelianPacket -> packet.counter.toUInt()
            else -> return Instant.now()
        }

        val previousTimerBits = device.timerBitsPreview ?: currentTimerBits

        val timeMultiplier = if (currentTimerBits < previousTimerBits) {
            ((ARGUS_MAX_TIMER_BITS + 1U) - previousTimerBits) + currentTimerBits
        } else {
            currentTimerBits - previousTimerBits
        }

        val captureTime = if (timeMultiplier == 1U) {
            val elapsedTime = when (packet) {
                is ArgusPacket -> packet.timer.toDouble() / if (packet.argusVersion == 2) ARGUS_2_TIME_DIVISOR else ARGUS_1_TIME_DIVISOR
                is AurelianPacket -> packet.timeElapsed / 32768.0
                else -> 0.0
            }
            val nano = (elapsedTime * 1000000).toLong()
            device.captureTimePreview?.plusNanos(nano) ?: Instant.now()
        } else {
            Instant.now()
        }

        device.captureTimePreview = captureTime
        device.timerBitsPreview = currentTimerBits

        return captureTime
    }

    @Synchronized
    suspend fun processStoredData(data: ByteArray, device: Device) {
        Log.d("DBG", "Processing stored data")

        when (device.deviceVersionInfo.deviceFamily) {
            Device.DeviceFamily.Argus -> processArgusStoredData(data, device)
            Device.DeviceFamily.Aurelian -> processAurelianStoredData(data, device)
            else -> {
                Log.d("DBG", "Unsupported device family")
                return
            }
        }
    }

    private suspend fun processArgusStoredData(data: ByteArray, device: Device) {
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS").withZone(ZoneOffset.UTC)

        for (i in 0 until data.size / ARGUS_DATA_PACKET_SIZE) {
            if (data.size >= (i + 1) * ARGUS_DATA_PACKET_SIZE) {
                val singleStorage = data.sliceArray(i * ARGUS_DATA_PACKET_SIZE until (i + 1) * ARGUS_DATA_PACKET_SIZE)
                processArgusStorageSegment(singleStorage, device, dateTimeFormatter)
            }
        }
    }

    private suspend fun processAurelianStoredData(data: ByteArray, device: Device) {
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS").withZone(ZoneOffset.UTC)

        for (i in data.indices step AURELIAN_PACKET_SIZE) {
            if (data.size >= i + AURELIAN_PACKET_SIZE) {
                val singleStorage = data.sliceArray(i until i + AURELIAN_PACKET_SIZE)
                processAurelianStorageSegment(singleStorage, device, dateTimeFormatter)
            }
        }
    }

    private suspend fun processArgusStorageSegment(singleStorage: ByteArray, device: Device, dateTimeFormatter: DateTimeFormatter) {
        val singleComp = singleStorage.sliceArray(0 until 8)

        when {
            singleComp.contentEquals(END_HISTORICAL) -> {
                Log.d("DBG", "End packet received")
                onTotalDownloadComplete(device)
                return
            }
            singleComp.contentEquals(START_HISTORICAL) -> handleStartHistorical(singleStorage, device)
            singleComp.contentEquals(START_TIMESTAMP) -> handleStartTimeStamp(singleStorage)
            else -> processArgusDataSegment(singleStorage, device, dateTimeFormatter)
        }
    }

    private suspend fun processAurelianStorageSegment(singleStorage: ByteArray, device: Device, dateTimeFormatter: DateTimeFormatter) {
        val singleComp = singleStorage.sliceArray(0 until 8)

        when {
            singleComp.contentEquals(END_HISTORICAL) -> {
                Log.d("DBG", "End packet received")
                onTotalDownloadComplete(device)
                return
            }
            singleComp.contentEquals(START_HISTORICAL) -> handleStartHistorical(singleStorage, device)
            singleComp.contentEquals(START_TIMESTAMP) -> handleStartTimeStamp(singleStorage)
            else -> processAurelianDataSegment(singleStorage, device, dateTimeFormatter)
        }
    }

    private fun handleStartHistorical(singleStorage: ByteArray, device: Device) {
        Log.d("DBG", "Start packet received")
        device.captureTimeStored = null
        device.timerBitsStored = 0u
        device.historyIndex = 1

        val packetCountBytes = singleStorage.sliceArray(8 until 12)
        val packetCount = (packetCountBytes[3].toInt() and 0xFF shl 24) or
                (packetCountBytes[2].toInt() and 0xFF shl 16) or
                (packetCountBytes[1].toInt() and 0xFF shl 8) or
                (packetCountBytes[0].toInt() and 0xFF)

        val DEVICE_PACKET_COUNT_MULTIPLIER = when (device.deviceVersionInfo.deviceFamily) {
            Device.DeviceFamily.Argus -> if (device.deviceVersionInfo.argusVersion >= 2) 1 else 1
            Device.DeviceFamily.Aurelian -> 5
            else -> 1
        }

        val totalPackets = packetCount * DEVICE_PACKET_COUNT_MULTIPLIER

        if (totalPackets == 0) {
            Log.d("DBG", "No historical data")
            onTotalDownloadComplete(device)
            return
        }

        device.isStreamingStoredData = true
        device.setTotalPackets(totalPackets)
        device.setCurrentPacket(0)
    }

    private fun handleStartTimeStamp(singleStorage: ByteArray) {
        Log.d("DBG", "Start timestamp packet received")
        val timestampBytes = singleStorage.sliceArray(8 until 16)
        var timestamp = 0L
        for (j in 0 until 8) {
            timestamp = timestamp shl 8
            timestamp = timestamp or (timestampBytes[j].toInt() and 0xFF).toLong()
        }
        initialHistoryCaptureTime = Instant.ofEpochMilli(timestamp)
        Log.d("DBG", "Initial history capture time: $initialHistoryCaptureTime")
    }

    private suspend fun processArgusDataSegment(singleStorage: ByteArray, device: Device, dateTimeFormatter: DateTimeFormatter) {
        val historyPacketData = parseArgusSegment(singleStorage, device)
        processHistoryPacketData(historyPacketData, device, dateTimeFormatter)
    }

    private suspend fun processAurelianDataSegment(singleStorage: ByteArray, device: Device, dateTimeFormatter: DateTimeFormatter) {
        val historyPacketDataList = parseAurelianPacket(singleStorage, device)
        historyPacketDataList.forEach { historyPacketData ->
            processHistoryPacketData(historyPacketData, device, dateTimeFormatter)
        }
    }

    private suspend fun processHistoryPacketData(historyPacketData: Packet, device: Device, dateTimeFormatter: DateTimeFormatter) {
        val captureTime = setCaptureTimeStoredData(historyPacketData, device)
        historyPacketData.captureTime = captureTime

        val captureTimeString = dateTimeFormatter.format(captureTime)
        val csvLine = buildCsvLine(device.historyIndex, captureTimeString, historyPacketData)

        device.dataAggregator.aggregateData(listOf(historyPacketData))

        device.historyIndex++
        device.setCurrentPacketv2(device.currentPacketv2.value + 1)
    }

    private fun setCaptureTimeStoredData(packet: Packet, device: Device): Instant {
        val currentTimerBits = when (packet) {
            is ArgusPacket -> packet.sequenceCounter.toUInt()
            is AurelianPacket -> packet.counter.toUInt()
            else -> return initialHistoryCaptureTime ?: Instant.now()
        }

        val previousTimerBits = device.timerBitsStored ?: currentTimerBits

        val timeMultiplier = if (currentTimerBits < previousTimerBits) {
            ((MAX_TIMER_BITS + 1U) - previousTimerBits) + currentTimerBits
        } else {
            currentTimerBits - previousTimerBits
        }

        val captureTime = if (timeMultiplier == 1U) {
            val elapsedTime = when (packet) {
                is ArgusPacket -> packet.timer.toDouble() / if (packet.argusVersion == 2) ARGUS_2_TIME_DIVISOR else ARGUS_1_TIME_DIVISOR
                is AurelianPacket -> (packet.timeElapsed / 32768.0)
                else -> 0.0
            }
            val nanos = (elapsedTime * 1_000_000_000).toLong()
            device.captureTimeStored?.plusNanos(nanos) ?: initialHistoryCaptureTime ?: Instant.now()
        } else {
            Log.w("DBG", "Unexpected time multiplier for stored data: $timeMultiplier")
            device.captureTimeStored ?: initialHistoryCaptureTime ?: Instant.now()
        }

        device.captureTimeStored = captureTime
        device.timerBitsStored = currentTimerBits

        return captureTime
    }

    private fun buildCsvLine(index: Int, captureTimeString: String, packet: Packet): String {
        return when (packet) {
            is ArgusPacket -> buildArgusCsvLine(index, captureTimeString, packet)
            is AurelianPacket -> buildAurelianCsvLine(index, captureTimeString, packet)
            else -> throw IllegalArgumentException("Unknown packet type")
        }
    }

    private fun buildArgusCsvLine(index: Int, captureTimeString: String, packet: ArgusPacket): String {
        return buildString {
            append("$index;")
            append("$captureTimeString;")
            append("${packet.mm660_8};${packet.mm660_30};${packet.mm660_35};${packet.mm660_40};")
            append("${packet.mm735_8};${packet.mm735_30};${packet.mm735_35};${packet.mm735_40};")
            append("${packet.mm810_8};${packet.mm810_30};${packet.mm810_35};${packet.mm810_40};")
            append("${packet.mm850_8};${packet.mm850_30};${packet.mm850_35};${packet.mm850_40};")
            append("${packet.mm890_8};${packet.mm890_30};${packet.mm890_35};${packet.mm890_40};")
            append("${packet.mmAmbient_8};${packet.mmAmbient_30};${packet.mmAmbient_35};${packet.mmAmbient_40};")
            append("${packet.temperature};")
            append("${packet.accelerometerX};${packet.accelerometerY};${packet.accelerometerZ};")
            append("${packet.timer};")
            append("${packet.sequenceCounter};")
            append("${if (packet.eventBit) "TRUE" else "FALSE"};")
            append("${packet.hbO2};${packet.hbd};")
            append("${packet.sessionId};")
            append("${packet.pulseRate};")
            append("${packet.respiratoryRate};")
            append("${packet.spO2};")
            append("${packet.ppgWaveform};")
            append("${packet.stO2};")
            append("${packet.reserved8};")
            append("${packet.reserved16};")
            append("${packet.reserved32}")
            appendLine()
        }
    }

    private fun buildAurelianCsvLine(index: Int, captureTimeString: String, packet: AurelianPacket): String {
        return buildString {
            append("$index;")
            append("$captureTimeString;")
            append("${packet.eegC1};${packet.eegC2};${packet.eegC3};${packet.eegC4};${packet.eegC5};${packet.eegC6};")
            append("${packet.accelerometerX};${packet.accelerometerY};${packet.accelerometerZ};")
            append("${packet.timeElapsed};")
            append("${packet.counter};")
            append("${if (packet.marker) "TRUE" else "FALSE"};")
            append("${packet.sessionId};")
            append("${packet.pulseRate};")
            append("${packet.tdcsImpedance};")
            append("${packet.tdcsCurrent};")
            append("${packet.tdcsOnTime};")
            append("${packet.batteryRSOC};")
            append("${packet.reserved8};")
            append("${packet.reserved64}")
            appendLine()
        }
    }

    private fun onTotalDownloadComplete(device: Device) {
        Log.d("DBG", "Total download complete")
        device.isStreamingStoredData = false
        device.historyIndex = 1
        device.setDownloadComplete(true)
    }


        private var initialHistoryCaptureTime: Instant? = null
        private const val MAX_TIMER_BITS = 0xFFFFFFFFU
        private val START_HISTORICAL = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        private val END_HISTORICAL = byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)
        private val START_TIMESTAMP = byteArrayOf(0x01, 0x03, 0x05, 0x07, 0x09, 0x0b, 0x0d, 0x0f)

}