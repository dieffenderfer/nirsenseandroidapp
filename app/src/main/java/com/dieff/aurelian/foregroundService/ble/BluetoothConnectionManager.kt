//package com.amplifilab.sensor.service
//
//import android.bluetooth.BluetoothGatt
//import android.bluetooth.BluetoothGattCallback
//import android.bluetooth.BluetoothGattCharacteristic
//import android.bluetooth.BluetoothProfile
//import android.content.Context
//import android.util.Log
//import com.amplifilab.sensor.data.local.repository.SensorRepository
//import com.amplifilab.sensor.model.IntensityPacket
//import com.amplifilab.sensor.usecases.SensorAlertUseCase
//import com.dieff.edppt.foregroundService.ble.Device
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.channels.awaitClose
//import kotlinx.coroutines.channels.trySendBlocking
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.callbackFlow
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import java.security.InvalidParameterException
//import java.util.UUID
//
//class BluetoothConnectionManager(
//    private val context: Context,
//    private val sensorRepository: SensorRepository,
//    private val sensorAlertUseCase: SensorAlertUseCase
//) {
//    private val connectionMap = LinkedHashMap<String, BluetoothGatt>()
//    private val serviceMap = LinkedHashMap<Device, BluetoothService>()
//    private val jobsMap = LinkedHashMap<Device, HashSet<Job>>()
//
//    private var retryCount = 0
//    private val sampleTimerMilliseconds = 1000L
//    private var shouldSampleData = false
//    private var shouldSampleAllData = false
//    private var timerJob: Job? = null
//
//    private val scope = CoroutineScope(Job() + Dispatchers.IO)
//
//    private val patientIdStartByte = 0x11.toByte()
//    private val CHARACTERISTIC_PREVIEW = UUID.fromString("c5a20002-566c-46fd-8c52-3e06820c7cea")
//    private val CHARACTERISTIC_STORAGE = UUID.fromString("c5a20003-566c-46fd-8c52-3e06820c7cea")
//    private val CHARACTERISTIC_BATTERY = UUID.fromString("c5a20004-566c-46fd-8c52-3e06820c7cea")
//    private val CHARACTERISTIC_COMMAND = UUID.fromString("c5a20005-566c-46fd-8c52-3e06820c7cea")
//    private val CHARACTERISTIC_CONFIGURATION = UUID.fromString("c5a20006-566c-46fd-8c52-3e06820c7cea")
//    private val CHARACTERISTIC_FIRMWARE = UUID.fromString("c5a20007-566c-46fd-8c52-3e06820c7cea")
//    private val CHARACTERISTIC_INTENSITY = UUID.fromString("c5a20008-566c-46fd-8c52-3e06820c7cea")
//    private val CHARACTERISTIC_STATUS = UUID.fromString("c5a20009-566c-46fd-8c52-3e06820c7cea")
//
//    private val COMMAND_START_BLINK = byteArrayOf(0x00)
//    private val COMMAND_STOP_BLINK = byteArrayOf(0x01)
//    private val COMMAND_BEGIN_SAMPLING = byteArrayOf(0x02)
//    private val COMMAND_STOP_SAMPLING = byteArrayOf(0x03)
//    private val COMMAND_SEND_STORED_DATA = byteArrayOf(0x04)
//    // Force sleep mode Not yet implemented
//    // private val COMMAND_SEND_STORED_DATA = byteArrayOf(0x05)
//    private val COMMAND_REQUEST_FIRMWARE = byteArrayOf(0x06)
//    private val COMMAND_REQUEST_EMITTER_VALUES = byteArrayOf(0x07)
//    private val COMMAND_MARK_EVENT = byteArrayOf(0x08)
//    private val COMMAND_CLEAR_FLASH = byteArrayOf(0x09)
//    private val COMMAND_SEND_STATUS = byteArrayOf(0x0A)
//    private val COMMAND_REQUEST_BATTERY = byteArrayOf(0x0B)
//    private val COMMAND_BLE_OFF = byteArrayOf(0x0C)
//    // 0x0D reserved
//    // 0x0E Enter DFU bootloader. Not needed for app
//    private val COMMAND_AUTO_CALIBRATE = byteArrayOf(0x10)
//    private val COMMAND_SEND_FLASH_COUNT = byteArrayOf(0x11)
//
//    private val CONFIGURATION_SAVE_PATIENT_ID_START = 0x11.toByte()
//    private val CONFIGURATION_PREVIEW_MODE_ON = byteArrayOf(0x05, 0x01)
//    private val CONFIGURATION_PREVIEW_MODE_OFF = byteArrayOf(0x05, 0x00)
//    private val CONFIGURATION_SAVE_MODE_ON = byteArrayOf(0x09, 0x01)
//    private val CONFIGURATION_SAVE_MODE_OFF = byteArrayOf(0x09, 0x00)
//
//    fun connect(patch: Device) {
//        if(!connectionMap.containsKey(patch.macAddressString)) {
//            jobsMap[patch] = HashSet()
//
//            scope.launch {
//                try {
//                    val device = withContext(Dispatchers.IO) {
//                        bluetoothAdapter.getRemoteDevice(patch.macAddressString)
//                    }
//
//                    val bluetoothGatt = withContext(Dispatchers.IO) {
//                        device.connectGatt(context, false, gattCallback)
//                    }
//
//                    connectionMap[patch.macAddressString] = bluetoothGatt
//                    serviceMap[patch] = BluetoothService(bluetoothGatt)
//                    onConnectedHandler(bluetoothGatt, patch)
//                } catch (e: Exception) {
//                    onConnectionFailed(e, patch)
//                }
//            }
//        }
//    }
//
//    private fun onConnectedHandler(gatt: BluetoothGatt, patch: Device) {
//        retryCount = 0
//        val jobs = jobsMap[patch] ?: return
//        val service = serviceMap[patch] ?: return
//
//        jobs.add(observeConnectionState(gatt, patch))
//        jobs.add(observeIntensityPacket(gatt, service))
//        jobs.add(observeBatteryLevel(gatt, service, patch))
//        jobs.add(observeFirmwareAndHardwareVersion(gatt, service, patch))
//        jobs.add(observeDeviceStatus(gatt, service, patch))
//       // sensorRepository.updateDeviceStatus(patch.macAddress, null)
//       // sensorRepository.updateDeviceIsConnected(patch.macAddress, true)
//
//        service.startPreview()
//        service.startSavingToDevice()
//        service.beginSampling()
//        service.requestFirmwareVersion()
//        service.requestBatteryLevel()
//        service.requestSensorStatus()
//    }
//
//    private fun onConnectionFailed(e: Exception, patch: Device) {
//        if(retryCount < 3) {
//            retryCount++
//            //Log.i("Waiting...")
//            Thread.sleep(500L)
//            //Log.i("Attempting to reconnect to MAC: ${patch.macAddressString}")
//
//            connect(patch)
//        } else {
//            //Log.e("Connection failure", e)
//            retryCount = 0
//        }
//    }
//
//    private fun observeConnectionState(gatt: BluetoothGatt, patch: Device): Job {
//        return scope.launch {
//            callbackFlow<Int> {
//                val callback = object : BluetoothGattCallback() {
//                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
//                        trySendBlocking(newState)
//                    }
//                }
//
//                gatt.registerCallback(callback)
//
//                awaitClose {
//                    gatt.close()
//                }
//            }.collect { newState ->
//                val isConnected = newState == BluetoothProfile.STATE_CONNECTED
//                sensorRepository.updateDeviceIsConnected(patch.macAddress, isConnected)
//            }
//        }
//    }
//
//    private fun observeBatteryLevel(gatt: BluetoothGatt, service: BluetoothService, patch: Device): Job {
//        return scope.launch {
//            service.observeBatteryLevel()
//                .collect { batteryLevel ->
//                    // if battery level is 0. it's being calculated. Ignore it
//                    if (batteryLevel > 0) {
//                        sensorRepository.updateDeviceBatteryLevel(patch.macAddress, batteryLevel)
//                    }
//                }
//        }
//    }
//
//    private fun observeFirmwareAndHardwareVersion(gatt: BluetoothGatt, service: BluetoothService, patch: Device): Job {
//        return scope.launch {
//            service.observeFirmwareAndHardwareVersion()
//                .collect { info ->
//                    sensorRepository.updateDeviceFirmware(patch, info.firmwareVersion)
//                    sensorRepository.updateDeviceHardware(patch, info.deviceFamily.value)
//                }
//        }
//    }
//
//    private fun observeDeviceStatus(gatt: BluetoothGatt, service: BluetoothService, patch: Device): Job {
//        return scope.launch {
//            service.observeDeviceStatus()
//                .collect { byteArray ->
//                    val status = "MyStatus"//ArgusDataConversion.parseStatus(byteArray, patch) //TODO
//                    patch.status = status
//                    sensorRepository.updateDeviceStatus(patch.macAddress, status)
//                }
//        }
//    }
//
//    private fun observeIntensityPacket(gatt: BluetoothGatt, service: BluetoothService): Job {
//        return scope.launch {
//            service.observeSensorEmissions()
//                .collect { rawData ->
//                    val isMarkEvent = rawData[61].toInt() and 0x80 != 0
////                    if (shouldSampleData || isMarkEvent || shouldSampleAllData) {
////                        val readingCache = ArgusDataConversion.parseReading(rawData, patch.macAddress, System.currentTimeMillis())
////                        sensorRepository.insertReading(readingCache)
////
////                        val alert = sensorAlertUseCase.checkReadingsWithinThreshold(
////                            patch,
////                            readingCache.spO2,
////                            readingCache.pr,
////                            readingCache.rr
////                        )
////
////                        if (alert != null) {
////                            sensorRepository.insertAlert(alert)
////                        }
////
////                        if (shouldSampleData && !shouldSampleAllData) {
////                            shouldSampleData = false
////                        }
//                    }
//                }
//        }
//    }
//
//    fun disconnectAll() {
//        for ((patch, _) in serviceMap) {
//            disconnect(patch)
//        }
//    }
//
//    fun disconnect(patch: Device) {
//        val gatt = connectionMap[patch.macAddressString] ?: return
//        val service = serviceMap[patch] ?: return
//        service.endPreview()
//        service.stopSampling()
//        service.stopSavingToDevice()
//
//        jobsMap[patch]?.forEach { it.cancel() }
//
//        connectionMap.remove(patch.macAddressString)
//        serviceMap.remove(patch)
//        jobsMap.remove(patch)
//        gatt.close()
//    }
//
//    fun restartPreviewAndSampling(patch: Device) {
//        val service = serviceMap[patch] ?: return
//        service.startPreview()
//        service.beginSampling()
//    }
//
//    fun markEvent(patch: Device) {
//        val service = serviceMap[patch] ?: return
//        service.markEvent()
//    }
//
//    fun sendPatientId(patch: Device, patientId: String) {
//        if (patientId.length <= 20) {
//            val data = ByteArray(20)
//            val strBytes = patientId.toByteArray()
//            System.arraycopy(strBytes, 0, data, data.size - strBytes.size, strBytes.size)
//
//            val service = serviceMap[patch] ?: return
//            service.savePatientId(data)
//        } else {
//            //Log.e("Invalid arg", "patientId must be shorter than 20 characters given length was ${patientId.length}")
//        }
//    }
//
//    fun requestSendStoredData(patch: Device) {
//        val service = serviceMap[patch] ?: return
//        service.requestSendStoredData()
//    }
//
//    fun observeStoredData(patch: Device): Flow<ByteArray> {
//        val service = serviceMap[patch] ?: return callbackFlow { }
//        return service.observeStoredData()
//    }
//
//    fun setShouldSaveDataToDevice(patch: Device, shouldSaveDataToDevice: Boolean) {
//        val service = serviceMap[patch] ?: return
//        if(shouldSaveDataToDevice) {
//            service.startSavingToDevice()
//        } else {
//            service.stopSavingToDevice()
//        }
//    }
//
//    fun shouldSampleAllData(shouldSampleData: Boolean) {
//        if(shouldSampleData) {
//            timerJob?.cancel()
//            shouldSampleAllData = true
//            this.shouldSampleData = true
//        } else {
//            shouldSampleAllData = false
//            timerJob = scope.launch {
//                while(true) {
//                    delay(sampleTimerMilliseconds)
//                    this@BluetoothConnectionManager.shouldSampleData = true
//                }
//            }
//        }
//    }
//
//    fun observeIntensityPacket(patch: Device): Flow<IntensityPacket> {
//        val service = serviceMap[patch] ?: return callbackFlow { }
//        return service.observeIntensityPacket()
//    }
//
//    fun requestIntensityPacket(patch: Device) {
//        val service = serviceMap[patch] ?: return
//        service.requestIntensityPacket()
//    }
//
//    fun setEmitterValue(patch: Device, byteArray: ByteArray): Flow<ByteArray> {
//        val service = serviceMap[patch] ?: return callbackFlow {  }
//        return service.setEmitterValue(byteArray)
//    }
//}
//
//private class BluetoothService(private val gatt: BluetoothGatt) {
//    fun startPreview() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_CONFIGURATION)
//        characteristic.value = CONFIGURATION_PREVIEW_MODE_ON
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun stopPreview() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_CONFIGURATION)
//        characteristic.value = CONFIGURATION_PREVIEW_MODE_OFF
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun beginSampling() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_COMMAND)
//        characteristic.value = COMMAND_BEGIN_SAMPLING
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun stopSampling() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_COMMAND)
//        characteristic.value = COMMAND_STOP_SAMPLING
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun startSavingToDevice() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(Characteristic.CONFIGURATION)
//        characteristic.value = CONFIGURATION_SAVE_MODE_ON
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun stopSavingToDevice() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_CONFIGURATION)
//        characteristic.value = CONFIGURATION_SAVE_MODE_OFF
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun endPreview() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_CONFIGURATION)
//        characteristic.value = CONFIGURATION_PREVIEW_MODE_OFF
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun observeSensorEmissions(): Flow<ByteArray> {
//        return callbackFlow {
//            val characteristic = gatt.getService(Characteristic.SERVICE)
//                .getCharacteristic(CHARACTERISTIC_PREVIEW)
//
//            gatt.setCharacteristicNotification(characteristic, true)
//
//            val callback = object : BluetoothGattCallback() {
//                override fun onCharacteristicChanged(
//                    gatt: BluetoothGatt,
//                    characteristic: BluetoothGattCharacteristic
//                ) {
//                    trySendBlocking(characteristic.value)
//                }
//            }
//
//            gatt.registerCallback(callback)
//
//            awaitClose {
//                gatt.setCharacteristicNotification(characteristic, false)
//                gatt.unregisterCallback(callback)
//            }
//        }
//    }
//
//    fun requestBatteryLevel() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_COMMAND)
//        characteristic.value = COMMAND_REQUEST_BATTERY
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun observeBatteryLevel(): Flow<Int> {
//        return callbackFlow {
//            val characteristic = gatt.getService(Characteristic.SERVICE)
//                .getCharacteristic(CHARACTERISTIC_BATTERY)
//
//            gatt.setCharacteristicNotification(characteristic, true)
//
//            val callback = object : BluetoothGattCallback() {
//                override fun onCharacteristicChanged(
//                    gatt: BluetoothGatt,
//                    characteristic: BluetoothGattCharacteristic
//                ) {
//                    val batteryLevel = characteristic.value[0].toInt()
//                    if (batteryLevel > 0) {
//                        trySendBlocking(batteryLevel)
//                    }
//                }
//            }
//
//            gatt.registerCallback(callback)
//
//            awaitClose {
//                gatt.setCharacteristicNotification(characteristic, false)
//                gatt.unregisterCallback(callback)
//            }
//        }
//    }
//
//    fun requestFirmwareVersion() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_COMMAND)
//        characteristic.value = COMMAND_REQUEST_FIRMWARE
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun observeFirmwareAndHardwareVersion(): Flow<Device.DeviceVersionInfo> {
//        return callbackFlow {
//            val characteristic = gatt.getService(Characteristic.SERVICE)
//                .getCharacteristic(CHARACTERISTIC_FIRMWARE)
//
//            gatt.setCharacteristicNotification(characteristic, true)
//
//            val callback = object : BluetoothGattCallback() {
//                override fun onCharacteristicChanged(
//                    gatt: BluetoothGatt,
//                    characteristic: BluetoothGattCharacteristic
//                ) {
//                    val bytes = characteristic.value
//                    val deviceFamilyNum = bytes[0].toInt()
//                    val deviceFamily = Device.DeviceFamily.fromInt(deviceFamilyNum)
//                    val firmwareVersion =
//                        "${bytes[1].toInt() and 0xff}.${bytes[2].toInt() and 0xff}"
//                    val info = Device.DeviceVersionInfo(firmwareVersion, deviceFamily)
//                    trySendBlocking(info)
//                }
//            }
//
//            gatt.registerCallback(callback)
//
//            awaitClose {
//                gatt.setCharacteristicNotification(characteristic, false)
//                gatt.unregisterCallback(callback)
//            }
//        }
//    }
//
//    fun requestSensorStatus() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_COMMAND)
//        characteristic.value = COMMAND_SEND_STATUS
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun observeDeviceStatus(): Flow<ByteArray> {
//        return callbackFlow {
//            val characteristic = gatt.getService(Characteristic.SERVICE)
//                .getCharacteristic(CHARACTERISTIC_STATUS)
//
//            gatt.setCharacteristicNotification(characteristic, true)
//
//            val callback = object : BluetoothGattCallback() {
//                override fun onCharacteristicChanged(
//                    gatt: BluetoothGatt,
//                    characteristic: BluetoothGattCharacteristic
//                ) {
//                    trySendBlocking(characteristic.value)
//                }
//            }
//
//            gatt.registerCallback(callback)
//
//            awaitClose {
//                gatt.setCharacteristicNotification(characteristic, false)
//                gatt.unregisterCallback(callback)
//            }
//        }
//    }
//
//    fun markEvent() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_COMMAND)
//        characteristic.value = COMMAND_MARK_EVENT
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun savePatientId(data: ByteArray) {
//        if (data.size != 20) {
//            throw InvalidParameterException("Patient id must be a length of 20.")
//        }
//
//        var sendingData = ByteArray(data.size + 1)
//        sendingData[0] = patientIdStartByte
//        System.arraycopy(data, 0, sendingData, 1, data.size)
//
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_CONFIGURATION)
//        characteristic.value = sendingData
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun requestSendStoredData() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_COMMAND)
//        characteristic.value = COMMAND_SEND_STORED_DATA
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun observeStoredData(): Flow<ByteArray> {
//        return callbackFlow {
//            val characteristic = gatt.getService(Characteristic.SERVICE)
//                .getCharacteristic(CHARACTERISTIC_STORAGE)
//
//            gatt.setCharacteristicNotification(characteristic, true)
//
//            val callback = object : BluetoothGattCallback() {
//                override fun onCharacteristicChanged(
//                    gatt: BluetoothGatt,
//                    characteristic: BluetoothGattCharacteristic
//                ) {
//                    trySendBlocking(characteristic.value)
//                }
//            }
//
//            gatt.registerCallback(callback)
//
//            awaitClose {
//                gatt.setCharacteristicNotification(characteristic, false)
//                gatt.unregisterCallback(callback)
//            }
//        }
//    }
//
//    fun requestIntensityPacket() {
//        val characteristic = gatt.getService(Characteristic.SERVICE)
//            .getCharacteristic(CHARACTERISTIC_COMMAND)
//        characteristic.value = COMMAND_REQUEST_EMITTER_VALUES
//        gatt.writeCharacteristic(characteristic)
//    }
//
//    fun observeIntensityPacket(): Flow<IntensityPacket> {
//        return callbackFlow {
//            val characteristic = gatt.getService(Characteristic.SERVICE)
//                .getCharacteristic(Characteristic.INTENSITY)
//
//            gatt.setCharacteristicNotification(characteristic, true)
//
//            val callback = object : BluetoothGattCallback() {
//                override fun onCharacteristicChanged(
//                    gatt: BluetoothGatt,
//                    characteristic: BluetoothGattCharacteristic
//                ) {
//                    val bytes = characteristic.value
//                    val packet = IntensityPacket(
//                        bytes.toIntBigEndian(0, 1),
//                        bytes.toIntBigEndian(2, 3),
//                        bytes.toIntBigEndian(4, 5),
//                        bytes.toIntBigEndian(6, 7),
//                        bytes.toIntBigEndian(8, 9)
//                    )
//                    trySendBlocking(packet)
//                }
//            }
//
//            gatt.registerCallback(callback)
//
//            awaitClose {
//                gatt.setCharacteristicNotification(characteristic, false)
//                gatt.unregisterCallback(callback)
//            }
//        }
//    }
//
//    fun setEmitterValue(byteArray: ByteArray): Flow<ByteArray> {
//        return callbackFlow {
//            val characteristic = gatt.getService(Characteristic.SERVICE)
//                .getCharacteristic(Characteristic.CONFIGURATION)
//            characteristic.value = byteArray
//            gatt.writeCharacteristic(characteristic)
//
//            awaitClose { }
//        }
//    }
//
//    private fun ByteArray.toIntBigEndian(start: Int, end: Int): Int {
//        var result = 0
//        for (i in start..end) {
//            result = result shl 8
//            result = result or (this[i].toInt() and 0xff)
//        }
//        return result
//    }
//}
//
//
// object Characteristic {
//     val SERVICE = UUID.fromString("c5a20001-566c-46fd-8c52-3e06820c7cea")
//    val PREVIEW = UUID.fromString("c5a20002-566c-46fd-8c52-3e06820c7cea")
//    val STORAGE = UUID.fromString("c5a20003-566c-46fd-8c52-3e06820c7cea")
//    val BATTERY = UUID.fromString("c5a20004-566c-46fd-8c52-3e06820c7cea")
//    val COMMAND = UUID.fromString("c5a20005-566c-46fd-8c52-3e06820c7cea")
//    val CONFIGURATION = UUID.fromString("c5a20006-566c-46fd-8c52-3e06820c7cea")
//    val FIRMWARE = UUID.fromString("c5a20007-566c-46fd-8c52-3e06820c7cea")
//    val INTENSITY = UUID.fromString("c5a20008-566c-46fd-8c52-3e06820c7cea")
//    val STATUS = UUID.fromString("c5a20009-566c-46fd-8c52-3e06820c7cea")
//}
//
//
// object Commands {
//    private val START_BLINK = byteArrayOf(0x00)
//    private val STOP_BLINK = byteArrayOf(0x01)
//    private val BEGIN_SAMPLING = byteArrayOf(0x02)
//    private val STOP_SAMPLING = byteArrayOf(0x03)
//    private val SEND_STORED_DATA = byteArrayOf(0x04)
//
//    // Force sleep mode Not yet implemented
//    // private static final byte[] SEND_STORED_DATA = new byte[] {0x05};
//    private val REQUEST_FIRMWARE = byteArrayOf(0x06)
//    private val REQUEST_EMITTER_VALUES = byteArrayOf(0x07)
//    private val MARK_EVENT = byteArrayOf(0x08)
//    private val CLEAR_FLASH = byteArrayOf(0x09)
//    private val SEND_STATUS = byteArrayOf(0x0A)
//    private val REQUEST_BATTERY = byteArrayOf(0x0B)
//    private val BLE_OFF = byteArrayOf(0x0C)
//
//    // 0x0D reserved
//    // 0x0E Enter DFU bootloader. Not needed for app
//    private val AUTO_CALIBRATE = byteArrayOf(0x10)
//    private val SEND_FLASH_COUNT = byteArrayOf(0x11)
//}
//
//
// object Configurations {
//    private const val SAVE_PATIENT_ID_START: Byte = 0x11
//    private val PREVIEW_MODE_ON = byteArrayOf(0x05, 0x01)
//    private val PREVIEW_MODE_OFF = byteArrayOf(0x05, 0x00)
//    private val SAVE_MODE_ON = byteArrayOf(0x09, 0x01)
//    private val SAVE_MODE_OFF = byteArrayOf(0x09, 0x00)
//}
