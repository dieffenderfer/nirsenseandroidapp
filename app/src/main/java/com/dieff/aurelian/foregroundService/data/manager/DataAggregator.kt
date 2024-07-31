//package com.dieff.aurelian.foregroundService.data.manager
//
//import android.content.Context
//import android.os.Environment
//import android.os.Handler
//import android.os.Looper
//import android.util.Log
//import com.dieff.aurelian.AppID
//import com.dieff.aurelian.foregroundService.ble.ArgusPacket
//import com.dieff.aurelian.foregroundService.ble.AurelianPacket
//import com.dieff.aurelian.foregroundService.ble.BleManager
//import com.dieff.aurelian.foregroundService.ble.BleManager.connectedDevices
//import com.dieff.aurelian.foregroundService.ble.Packet
//import com.dieff.aurelian.globalAppContext
//import com.dieff.aurelian.globalAppID
//import com.dieff.aurelian.ui.viewmodel.packetChannelDataAggregatorToFragment
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import java.io.File
//import java.io.FileWriter
//import java.io.IOException
//
//object DataAggregator {
//
//    // Initialize ARRAY_SIZE_GRAPHING based on the value of globalAppID //FIX_ME TODO add other app ids; or refactor because we want to support aerie and aurelian app mix, so it wouldn't be fixed array size graphing based on an app id; the multi connection app is going to need individual arrays per device anyway
//    //TODO FIX_ME This shouldn't be an app-wide setting
//    private val ARRAY_SIZE_GRAPHING: Int = when (globalAppID) {
//        AppID.AURELIAN_APP -> 5
//        AppID.ARGUS_APP -> 6
//        AppID.ANY_DEVICE_APP -> 6
//    }
//    // Initialize ARRAY_SIZE_SAVING based on ARRAY_SIZE_GRAPHING
//    private val ARRAY_SIZE_SAVING: Int = ARRAY_SIZE_GRAPHING * 100
//
//    private var dbAggregateArrayGraphing: Array<Packet?> = arrayOfNulls(ARRAY_SIZE_GRAPHING)
//    private var dbAggregateArraySaving: Array<Packet?> = arrayOfNulls(ARRAY_SIZE_SAVING)
//    private var currentIndexGraphing = 0
//    private var currentIndexSaving = 0
//
//    // Create scope for coroutine to run in
//    private val scope = CoroutineScope(Dispatchers.IO)
//
//    private fun printPacket(packet: Packet) {
//        when (packet) {
//            is ArgusPacket -> Log.d("DBG", "Argus packet aggregated. Sequence counter: ${packet.sequenceCounter}")
//            is AurelianPacket -> Log.d("DBG", "Aurelian packet aggregated. Counter: ${packet.counter}")
//        }
//    }
//
//    fun aggregateData(packets: List<Packet>) {
//        packets.forEach { packet ->
//            //Graphing
//            if (currentIndexGraphing < ARRAY_SIZE_GRAPHING) {
//                dbAggregateArrayGraphing[currentIndexGraphing] = packet
//                currentIndexGraphing++
//            }
//
//            if (currentIndexGraphing == ARRAY_SIZE_GRAPHING) {
//                printPacket(packet)
//                fwdPacketsFromDataAggregatorToGraphingFragment(dbAggregateArrayGraphing)
//                currentIndexGraphing = 0
//            }
//
//            //Saving to db
//            if (currentIndexSaving < ARRAY_SIZE_SAVING) {
//                dbAggregateArraySaving[currentIndexSaving] = packet
//                currentIndexSaving++
//            }
//
//            if (currentIndexSaving >= ARRAY_SIZE_SAVING) {
//                Log.d("DBG", "Aggregated SAVING data 500 times.")
//                currentIndexSaving = 0
//                saveToCSV(globalAppContext, dbAggregateArraySaving)
//            }
//        }
//    }
//
//    private fun fwdPacketsFromDataAggregatorToGraphingFragment(thedata: Array<Packet?>) {
//        Log.d("DBG","  DataAggregator - Entered fwdPacketsFromDataAggregatorToGraphingFragment")
//        scope.launch {
//  // Loops until channel is closed
//            packetChannelDataAggregatorToFragment.trySend(thedata)
//                Log.d("DBG","  DataAggregator - IN - receiver")
//
//            Log.d("DBG","EXITED DataAggregator loop")
//        }
//        Log.d("DBG","  DataAggregator - Exited  fwdPacketsFromDataAggregatorToGraphingFragment")
//    }
//
//    private fun saveToCSV(context: Context, data: Array<Packet?>) {
//        val firstPacket = data.firstOrNull() ?: return
//        val deviceMacAddress = firstPacket.deviceMacAddress
//        val device = connectedDevices.value.find { it.macAddress == deviceMacAddress } //TODO CHECK this
//        val filename = device?.filename
//        val filePath = filename?.let { findOrCreateFile(it, firstPacket, device.metadataText ?: "") }
//        val saveString = StringBuilder()
//
//        var index = device?.index ?: 1
//
//        for (datum in data) {
//            saveString.append(buildCsvLine(index, datum))
//            index += 1
//        }
//
//        device?.index = index
//
//        if (filePath != null) {
//            appendStringToCSV(filePath, saveString.toString())
//        }
//    }
//
//    private fun findOrCreateFile(filename: String, packet: Packet, metadata: String): String {
//        Log.d("DBG", "Entering findOrCreateFile function")
//        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "NIRSense")
//        if (!directory.exists()) {
//            Log.d("DBG", "Directory does not exist, creating it")
//            directory.mkdirs()
//        } else {
//            Log.d("DBG", "Directory already exists")
//        }
//
//        val sanitizedFilename = sanitizeFilename(filename)
//        Log.d("DBG", "Sanitized filename: $sanitizedFilename")
//
//        val myFile = File(directory, "$sanitizedFilename.csv")
//        if (!myFile.exists()) {
//            Log.d("DBG", "File does not exist, creating it")
//            myFile.createNewFile()
//
//            // Write the header to the new file
//            val header = getHeader(packet, metadata)
//            myFile.writeText(header)
//        } else {
//            Log.d("DBG", "File already exists")
//        }
//        Log.d("DBG", "Returning file path: ${myFile.absolutePath}")
//        return myFile.absolutePath
//    }
//
//    private fun getHeader(packet: Packet, metadata: String): String {
//        return when (packet) {
//            is ArgusPacket -> getArgusHeader(metadata)
//            is AurelianPacket -> getAurelianHeader(metadata)
//            else -> throw IllegalArgumentException("Unknown packet type")
//        }
//    }
//
//    private fun buildCsvLine(index: Int, packet: Packet?): String {
//        return when (packet) {
//            is ArgusPacket -> buildArgusCsvLine(index, packet)
//            is AurelianPacket -> buildAurelianCsvLine(index, packet)
//            else -> ""
//        }
//    }
//
//    private fun buildArgusCsvLine(index: Int, packet: ArgusPacket): String {
//        return "$index;${packet.captureTime};${packet.mm660_8};${packet.mm660_30};${packet.mm660_35};${packet.mm660_40};" +
//                "${packet.mm735_8};${packet.mm735_30};${packet.mm735_35};${packet.mm735_40};${packet.mm810_8};${packet.mm810_30};" +
//                "${packet.mm810_35};${packet.mm810_40};${packet.mm850_8};${packet.mm850_30};${packet.mm850_35};${packet.mm850_40};" +
//                "${packet.mm890_8};${packet.mm890_30};${packet.mm890_35};${packet.mm890_40};${packet.mmAmbient_8};${packet.mmAmbient_30};" +
//                "${packet.mmAmbient_35};${packet.mmAmbient_40};${packet.temperature};${packet.accelerometerX};${packet.accelerometerY};" +
//                "${packet.accelerometerZ};${packet.timer};${packet.sequenceCounter};${if (packet.eventBit) "TRUE" else "FALSE"};${packet.hbO2};" +
//                "${packet.hbd};${packet.sessionId};${packet.pulseRate};${packet.respiratoryRate};${packet.spO2};${packet.ppgWaveform};" +
//                "${packet.stO2};${packet.reserved8};${packet.reserved16};${packet.reserved32}\n"
//    }
//
//    private fun buildAurelianCsvLine(index: Int, packet: AurelianPacket): String {
//        return "$index;${packet.captureTime};${packet.eegC1};${packet.eegC2};${packet.eegC3};${packet.eegC4};${packet.eegC5};${packet.eegC6};" +
//                "${packet.accelerometerX};${packet.accelerometerY};${packet.accelerometerZ};${packet.timeElapsed};${packet.counter};" +
//                "${if (packet.marker) "TRUE" else "FALSE"};${packet.sessionId};${packet.pulseRate};${packet.tdcsImpedance};${packet.tdcsCurrent};" +
//                "${packet.tdcsOnTime};${packet.batteryRSOC};${packet.reserved8};${packet.reserved64}\n"
//    }
//
//
//    private fun getArgusHeader(metadata: String): String {
//        return "Index;Capture Time;660-8mm;660-30mm;660-35mm;660-40mm;735-8mm;735-30mm;735-35mm;735-40mm;810-8mm;810-30mm;810-35mm;810-40mm;850-8mm;850-30mm;850-35mm;850-40mm;890-8mm;890-30mm;890-35mm;890-40mm;Ambient-8mm;Ambient-30mm;Ambient-35mm;Ambient-40mm;Temperature;AccelX;AccelY;AccelZ;Time_Elapsed;Counter;Marker;HBO2;HBD;Session;Pulse_Rate;Respiratory_Rate;SpO2;PPG;StO2;Reserved8;Reserved16;Reserved32;$metadata\n"
//    }
//
//    private fun getAurelianHeader(metadata: String): String {
//        return "Index;Capture Time;EEG_C1;EEG_C2;EEG_C3;EEG_C4;EEG_C5;EEG_C6;AccelX;AccelY;AccelZ;Time_Elapsed;Counter;Marker;Session;Pulse_Rate;tDCS_Impedance;tDCS_Current;tDCS_On_Time;Battery_RSOC;Reserved8;Reserved64;$metadata\n"
//    }
//
//    private fun appendStringToCSV(filePath: String, data: String) {
//        Log.d("DBG", "Entering appendStringToCSV function")
//        val handler = Handler(Looper.getMainLooper())
//        Thread {
//            try {
//                Log.d("DBG", "Starting file write operation")
//                val fileWriter = FileWriter(filePath, true)
//                fileWriter.write(data)
//                fileWriter.flush()
//                fileWriter.close()
//                Log.d("DBG", "File write operation completed successfully")
//
//                handler.post {
//                    Log.d("DBG", "Updating UI on the main thread")
//
//                }
//            } catch (e: IOException) {
//                e.printStackTrace()
//                Log.d("DBG", "Error occurred during file write operation")
//                handler.post {
//                    Log.d("DBG", "Handling error on the main thread")
//
//                }
//            }
//        }.start()
//    }
//
//    fun sanitizeFilename(filename: String): String {
//        Log.d("DBG", "Entering sanitizeFilename function")
//        // Characters not allowed in filenames on Android: / \ : * ? " < > |
//        val illegalCharacters = "[/\\\\:*?\"<> |]".toRegex()
//
//        // Replace illegal characters with an underscore
//        var sanitizedFilename = filename.replace(illegalCharacters, "_")
//        Log.d("DBG", "Replaced illegal characters: $sanitizedFilename")
//
//        // Trim whitespace and dots from the start and end of the filename
//        sanitizedFilename = sanitizedFilename.trim().trimStart('.').trimEnd('.')
//        Log.d("DBG", "Trimmed whitespace and dots: $sanitizedFilename")
//
//        // Truncate the filename if it exceeds the maximum length (255 characters)
//        if (sanitizedFilename.length > 255) {
//            Log.d("DBG", "Filename exceeds maximum length, truncating")
//            sanitizedFilename = sanitizedFilename.substring(0, 255)
//        }
//
//        Log.d("DBG", "Returning sanitized filename: $sanitizedFilename")
//        return sanitizedFilename
//    }
//
//}