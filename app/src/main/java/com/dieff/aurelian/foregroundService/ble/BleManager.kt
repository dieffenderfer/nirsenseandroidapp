package com.dieff.aurelian.foregroundService.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dieff.aurelian.APP_CONN_STATE
import com.dieff.aurelian.CCC_DESCRIPTOR_UUID
import com.dieff.aurelian.foregroundService.data.manager.DataParser
import com.dieff.aurelian.foregroundService.statusChannelServiceToRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * BleManager: Singleton object responsible for managing Bluetooth Low Energy (BLE) connections and operations.
 * It handles device discovery, connection, communication, and data processing for BLE devices.
 */
object BleManager : Application() {
    // Coroutine scope for asynchronous operations
    val scope = CoroutineScope(Dispatchers.Main)

    // StateFlow to hold and observe the list of connected devices
    private val _connectedDevices = MutableStateFlow<List<Device>>(emptyList())
    val connectedDevices: StateFlow<List<Device>> = _connectedDevices.asStateFlow()

    // StateFlow to hold and observe the list of all devices (connected and disconnected)
    private val _allDevices = MutableStateFlow<List<Device>>(emptyList())
    val allDevices: StateFlow<List<Device>> = _allDevices.asStateFlow()

    // Connection attempt counters
    private var rcCnt = 0
    private var rcCntMax = 5
    private var numConnects = 0

    // Connection state variables
    lateinit var connectState: ConnState
    private var deviceAddress: String? = null
    private var disconnectAndRetry: Boolean = false
        set(value) {
            Log.d("DBG", "disconnectAndRetry update = $value ")
            field = value
        }

    // Application connection state
    private var appConnState: String = "UNDEFINED"
        set(value) {
            val statusPacket = StatusPacket(APP_CONN_STATE, value)
            fwdStatusBleToRepository(statusPacket)
            Log.d("DBG", "appConnState update = $value ")
            field = value
        }
    private var connectTimestamp: Long = 0

    // Current BLE device being processed
    private lateinit var currentBleDevice: ScanResult

    // MTU (Maximum Transmission Unit) size for BLE communication
    private var mtuSize = 23
    private const val mtuSizeRequest = 517

    // UUID constants for BLE services and characteristics
    val SERVICE = UUID.fromString("c5a20001-566c-46fd-8c52-3e06820c7cea")
    val PREVIEW = UUID.fromString("c5a20002-566c-46fd-8c52-3e06820c7cea")
    val STORAGE = UUID.fromString("c5a20003-566c-46fd-8c52-3e06820c7cea")
    val BATTERY = UUID.fromString("c5a20004-566c-46fd-8c52-3e06820c7cea")
    val COMMAND = UUID.fromString("c5a20005-566c-46fd-8c52-3e06820c7cea")
    val CONFIGURATION = UUID.fromString("c5a20006-566c-46fd-8c52-3e06820c7cea")
    val FIRMWARE = UUID.fromString("c5a20007-566c-46fd-8c52-3e06820c7cea")
    val INTENSITY = UUID.fromString("c5a20008-566c-46fd-8c52-3e06820c7cea")
    val STATUS = UUID.fromString("c5a20009-566c-46fd-8c52-3e06820c7cea")

    private val COMMAND_UUID: UUID = COMMAND
    private val CONFIG_UUID: UUID = CONFIGURATION

    private var packetCount = -1

    // Queue for onboarding devices one at a time
    private val onboardingQueue = ArrayDeque<Device>()
    private var isOnboarding = false

    /**
     * Enum class representing different states of device setup process
     */
    enum class SetupState(val stateNumber: Int) {
        DISCONNECTED(1),
        CONNECTING(2),
        CONNECTED(3),
        SERVICES_DISCOVERED(4),
        NOTIFICATIONS_ENABLED(5),
        BATTERY_RECEIVED(6),
        SAMPLING_STOPPED(7),
        FIRMWARE_RECEIVED(8),
        NVM_RECEIVED(9),
        PREVIEW_MODE_ENABLED(10),
        TIMESTAMP_SENT(11),
        SAVE_MODE_ENABLED(12),
        SETUP_COMPLETE(13);

        companion object {
            /**
             * Returns a formatted string representation of the current setup state
             */
            fun getFormattedState(state: SetupState): String {
                val totalStates = values().size
                return "Current State: ${state.name} (${state.stateNumber} out of $totalStates)"
            }
        }
    }

    /**
     * Initiates a BLE connection to the specified device
     * @param scanResult The ScanResult object containing device information
     * @param isDelayed Boolean indicating if the connection should be delayed
     * @param isRetry Boolean indicating if this is a retry attempt
     */
    @SuppressLint("MissingPermission")
    fun connectBle(scanResult: ScanResult, isDelayed: Boolean, isRetry: Boolean) {
        Log.d("DBG", "Entered connectBle")
        if (!isRetry) {
            rcCnt = 0
            currentBleDevice = scanResult
        }
        val delay: Long = if (isDelayed) 5000 else 0
        val startTime = SystemClock.elapsedRealtime()
        Log.d("DBG", "CALLING CONNECT LOOPER at time = $startTime")
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d("DBG", "    device.connectGatt issued for ${scanResult.device.name}")
            appConnState = "CONNECTING"
            connectTimestamp = SystemClock.elapsedRealtime()
            Log.d("DBG", "IN CONNECT LOOPER at time = $connectTimestamp} delta = ${connectTimestamp - startTime}")
            scanResult.device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }, delay)
    }

    /**
     * For when the user wants to manually remove a device from the list of all devices and closes its GATT connection
     * (The user's intent makes this different than an unintended disconnect)
     * @param device The device to be removed
     */
    @SuppressLint("MissingPermission")
    fun removeDevice(device: Device) {
        scope.launch {
            Log.d("DBG", "Manually removing device ${device.macAddressString}")
            device.bluetoothGatt.disconnect()
            device.bluetoothGatt.close()
            _allDevices.update { it.filter { d -> d.macAddress != device.macAddress } }
            _connectedDevices.update { it.filter { d -> d.macAddress != device.macAddress } }
        }
    }

    /**
     * Callback object for handling GATT (Generic Attribute Profile) events
     */
    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        /**
         * Called when the connection state changes
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                Log.d("DBG", "      BleManager Entered onConnectionStateChange")
                val timePassed = SystemClock.elapsedRealtime() - connectTimestamp
                Log.d("DBG", " Connection time = $timePassed")
                val prevConnectState = connectState

                connectState = ConnState.fromValue(newState)
                Log.d("DBG", "      BleManager - onConnectionStateChange prevConnectState = $prevConnectState")
                Log.d("DBG", "      BleManager - onConnectionStateChange ConnectState = $connectState")

                deviceAddress = gatt.device.address

                val currentDateString = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
                val newfilename = sanitizeFilename("${gatt.device.name}_$currentDateString")

                // Find or create a device object
                var device = _connectedDevices.value.find { it.macAddressString == gatt.device.address }
                if (device == null) {
                    device = Device(gatt, newfilename, SetupState.DISCONNECTED)
                    _connectedDevices.update { it + device }
                } else {
                    _connectedDevices.update { devices ->
                        devices.map {
                            if (it.macAddressString == gatt.device.address) {
                                it.bluetoothGatt = gatt
                                it.filename = newfilename
                                it
                            } else it
                        }
                    }
                }

                Log.d("DBG", "Device info: " + device.getDeviceInfo())

                val hciStatus = HciStatus.fromValue(status)
                Log.d("DBG", "      BleManager - onConnectionStateChange hciStatus = $hciStatus")

                if (hciStatus == HciStatus.SUCCESS) {
                    when (connectState) {
                        ConnState.CONNECTED -> {
                            connected(device)
                            if (device.hasCompletedSetupBefore) {
                                updateDeviceSetupState(device, SetupState.SETUP_COMPLETE)
                            } else {
                                addToOnboardingQueue(device)
                            }
                        }
                        ConnState.DISCONNECTED -> disconnected(prevConnectState, device)
                        ConnState.DISCONNECTING -> Log.d("DBG", "  Peripheral is DISCONNECTING")
                        ConnState.CONNECTING -> Log.d("DBG", "  Peripheral is CONNECTING")
                        else -> Log.d("DBG", "  UNKNOWN CONNECTION STATE: $connectState")
                    }
                } else {
                    connectionStateChangeError(hciStatus, prevConnectState, connectState)
                }
            }
        }

        /**
         * Called when services are discovered on the remote device
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            scope.launch {
                Log.d("DBG", "Entered onServiceDiscovered")
                val gattStatus = GattStatus.fromValue(status)
                if (gattStatus != GattStatus.SUCCESS) {
                    Log.e("DBG", "Error status for onServicesDiscovered: $gattStatus")
                    disconnectAndRetry = true
                    disconnectBle()
                    return@launch
                }
                val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                Log.d("DBG", "Discovered ${device?.bluetoothGatt?.services?.size} services for ${device?.macAddress}.")
                device?.bluetoothGatt?.printGattTable()
                updateDeviceSetupState(device!!, SetupState.SERVICES_DISCOVERED)
                negotiateMtuSize(mtuSizeRequest)
            }
        }

        /**
         * Called when the MTU for a given connection changes
         */
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            scope.launch {
                Log.d("DBG", "Entered onMtuChanged")
                when (status) {
                    BluetoothGatt.GATT_FAILURE -> {
                        Log.d("DBG", "mtu change request failed, assuming device does not support this function")
                        Log.d("DBG", "setting mtuSize to minimum of 23 bytes")
                        mtuSize = 23
                    }
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.d("DBG", "mtu size set to $mtu")
                        mtuSize = mtu
                    }
                }
                Log.d("DBG", "calling notificationControl (enable) from onMtuChanged")
                if (gatt != null) {
                    enableNotificationsForAllCharacteristics(gatt)
                }
            }
        }

        /**
         * Called when the characteristic changes (for Android 11 legacy compability)
         */
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicChanged(gatt, characteristic, characteristic.value)
        }

        /**
         * Called when the characteristic changes (for Android 12+)
         */
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        /**
         * Handles characteristic changes (for legacy and modern versions of Android)
         */
        private fun handleCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            scope.launch {
                Log.d("DBG", "Received Bluetooth packet of size ${value.size}")
                Log.d("DBG", "Entered onCharacteristicChanged in response to notification request")
                if (characteristic != null) {
                    with(characteristic) {
                        ++packetCount
                        val newMessageString = value.joinToString(separator = " ") { String.format("%02X", it) }
                        Log.i("DBG", "Characteristic $uuid changed | packet: $packetCount value: $newMessageString")
                        when (uuid) {
                            PREVIEW -> {
                                val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                                device?.let {
                                    DataParser.processPreviewData(value, it)
                                }
                            }
                            STORAGE -> {
                                Log.d("DBG", "Received data from STORAGE characteristic")
                                val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                                device?.let {
                                    var chunkSize = if (device.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Aurelian) 120 else 80 //TODO FIX_ME Refactor this to handle different device types better. Could be something like Device.chunksize that gets set on onboarding
                                    var offset = 0
                                    while (offset < value.size) {
                                        val end = minOf(offset + chunkSize, value.size)
                                        var chunk = value.copyOfRange(offset, end)
                                        if (chunk.size < chunkSize) {
                                            val paddedChunk = ByteArray(chunkSize)
                                            System.arraycopy(chunk, 0, paddedChunk, 0, chunk.size)
                                            chunk = paddedChunk
                                            Log.d("DBG", "Padded chunk with 0x00 bytes to meet chunkSize requirement")
                                        }
                                        DataParser.processStoredData(chunk, it)
                                        offset += chunkSize
                                    }
                                }
                            }
                            STATUS -> {
                                Log.d("DBG", "Received data from STATUS characteristic")
                                val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                                device?.let {
                                    val statusBytes = value
                                    if (statusBytes.size >= 5) {
                                        when (statusBytes[0]) {
                                            0x05.toByte() -> {
                                                val nvmVersion: Int = (statusBytes[4].toInt() and 0xFF shl 24) or
                                                        (statusBytes[3].toInt() and 0xFF shl 16) or
                                                        (statusBytes[2].toInt() and 0xFF shl 8) or
                                                        (statusBytes[1].toInt() and 0xFF)
                                                device.deviceVersionInfo.nvmVersion = nvmVersion
                                                Log.d("DBG", "STATUS nvm version received, device.deviceVersionInfo.nvmVersion = ${device.deviceVersionInfo.nvmVersion}")
                                                updateDeviceSetupState(device, SetupState.NVM_RECEIVED)
                                            }
                                        }
                                    }
                                }
                            }
                            BATTERY -> {
                                Log.d("DBG", "Received data from BATTERY characteristic")
                                val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                                device?.let {
                                    if (value.isNotEmpty()) {
                                        val batteryLevel = value[0].toInt()
                                        device.battery = batteryLevel
                                        Log.d("DBG", "Battery level received: $batteryLevel%")
                                        updateDeviceSetupState(device, SetupState.BATTERY_RECEIVED)
                                    } else {
                                        Log.e("DBG", "Received empty battery data")
                                    }
                                }
                            }
                            FIRMWARE -> {
                                Log.d("DBG", "Received data from FIRMWARE characteristic")
                                Log.i("DBG", "FIRMWARE value: $newMessageString")
                                val bytes = value
                                if (bytes.size == 3) {
                                    val deviceFamilyNum = bytes[0].toInt()
                                    val deviceFamily = Device.DeviceFamily.fromInt(deviceFamilyNum)
                                    val firmwareVersion = "${bytes[1].toInt() and 0xff}.${bytes[2].toInt() and 0xff}"
                                    var argusVersion: Int = 0
                                    if (deviceFamily == Device.DeviceFamily.Argus) {
                                        argusVersion = if (bytes[1].toInt() >= 5) 2 else 1
                                    }
                                    if (firmwareVersion.toDouble() >= 170.0) {
                                        argusVersion = 2
                                    }

                                    Log.i("DBG", "FIRMWARE info: deviceFamily $deviceFamily and firmwareVersion $firmwareVersion and argusVersion $argusVersion")
                                    val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                                    device?.let {
                                        Log.i("DBG", "FIRMWARE assigning info to device, currently it is ${it.deviceVersionInfo}")
                                        val info = Device.DeviceVersionInfo(
                                            firmwareVersion,
                                            it.deviceVersionInfo.nvmVersion,
                                            deviceFamily,
                                            argusVersion
                                        )
                                        it.deviceVersionInfo = info
                                        Log.i("DBG", "FIRMWARE assigned info to device, now it is ${it.deviceVersionInfo}")
                                        updateDeviceSetupState(it, SetupState.FIRMWARE_RECEIVED)
                                    } ?: run {
                                        Log.i("DBG", "FIRMWARE failed to assign info to device")
                                    }
                                } else {
                                    Log.e("DBG", "Unexpected firmware data size: ${bytes.size}")
                                }
                            }
                            else -> {
                                Log.d("DBG", "Received data from unknown characteristic: $uuid")
                            }
                        }
                    }
                } else {
                    Log.e("DBG", "onCharacteristicChanged returned null characteristic")
                }
            }
        }

        /**
         * Called when a characteristic read operation completes
         */
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d("DBG", "Entered onCharacteristicRead for ${characteristic.uuid}")
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("DBG", "Characteristic ${characteristic.uuid} read successfully, value: ${value.toHexString()}")
                    when (characteristic.uuid) {
                        CONFIG_UUID -> {
                            val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                            device?.let {
                                when {
                                    value.contentEquals(Configurations.PREVIEW_MODE_ON) -> {
                                        Log.d("DBG", "Preview mode confirmed for ${it.macAddressString}")
                                        updateDeviceSetupState(it, SetupState.PREVIEW_MODE_ENABLED)
                                    }
                                    value.contentEquals(Configurations.SAVE_MODE_ON) -> {
                                        Log.d("DBG", "Save mode confirmed for ${it.macAddressString}")
                                        updateDeviceSetupState(it, SetupState.SAVE_MODE_ENABLED)
                                    }
                                    else -> {
                                        Log.d("DBG", "Unexpected value for CONFIG_UUID: ${value.toHexString()}")
                                    }
                                }
                            }
                        }
                        COMMAND_UUID -> {
                            val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                            device?.let {
                                if (value.contentEquals(Commands.STOP_SAMPLING)) {
                                    Log.d("DBG", "Sampling stopped confirmed for ${it.macAddressString}")
                                    updateDeviceSetupState(it, SetupState.SAMPLING_STOPPED)
                                } else {
                                    Log.d("DBG", "Unexpected value for COMMAND_UUID: ${value.toHexString()}")
                                }
                            }
                        }
                        else -> {
                            Log.d("DBG", "Read characteristic ${characteristic.uuid}, value: ${value.toHexString()}")
                        }
                    }
                } else {
                    Log.e("DBG", "Characteristic read failed: $status for ${characteristic.uuid}")
                }
            }
        }

        /**
         * Called when a characteristic write operation completes
         */
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            scope.launch {
                Log.d("DBG", "Entered onCharacteristicWrite")
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.d("DBG", "Write characteristic $characteristic succeeded!")
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e("DBG", "Write characteristic $characteristic NOT permitted!")
                    }
                    else -> {
                        Log.e("DBG", "Write characteristic $characteristic failed with error: $status")
                    }
                }
            }
        }

        /**
         * Called when a descriptor read operation completes
         */
        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            scope.launch {
                Log.d("DBG", "Entered onDescriptorRead")
                // TODO: Implement descriptor read handling if needed
            }
        }

        /**
         * Enables notifications for all characteristics of the device
         */
        private fun enableNotificationsForAllCharacteristics(gatt: BluetoothGatt) {
            scope.launch {
                Log.d("DBG", "Entered enableNotificationsForAllCharacteristics")
                val service = gatt.getService(SERVICE)
                if (service != null) {
                    Log.d("DBG", "Found service with UUID: ${service.uuid}")
                    service.characteristics?.forEach { characteristic ->
                        Log.d("DBG", "Checking characteristic ${characteristic.uuid}")
                        if (characteristic.isNotifiable()) {
                            Log.d("DBG", "Characteristic ${characteristic.uuid} is notifiable")
                            val descriptor = characteristic.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID))
                            if (descriptor != null) {
                                Log.d("DBG", "Found CCC Descriptor for characteristic ${characteristic.uuid}")
                                var notificationSuccess = false
                                while (!notificationSuccess) {
                                    if (gatt.setCharacteristicNotification(characteristic, true)) {
                                        Log.d("DBG", "setCharacteristicNotification succeeded for ${characteristic.uuid}")
                                        notificationSuccess = true
                                    } else {
                                        Log.e("DBG", "setCharacteristicNotification failed for ${characteristic.uuid}")
                                        delay(100) // Add a small delay before retrying
                                    }
                                }
                                var descriptorWriteSuccess = false
                                while (!descriptorWriteSuccess) {
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    if (gatt.writeDescriptor(descriptor)) {
                                        Log.d("DBG", "writeDescriptor succeeded for ${characteristic.uuid}")
                                        descriptorWriteSuccess = true
                                    } else {
                                        Log.e("DBG", "writeDescriptor failed for ${characteristic.uuid}")
                                        delay(100) // Add a small delay before retrying
                                    }
                                }
                            } else {
                                Log.e("DBG", "CCC Descriptor not found for notifiable characteristic ${characteristic.uuid}")
                            }
                        } else {
                            Log.d("DBG", "Characteristic ${characteristic.uuid} is not notifiable")
                        }
                    }

                    val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                    device?.let {
                        updateDeviceSetupState(it, SetupState.NOTIFICATIONS_ENABLED)
                    }
                } else {
                    Log.e("DBG", "Service with UUID $SERVICE not found")
                }
                Log.d("DBG", "Exited enableNotificationsForAllCharacteristics")
            }
        }
    }

    /**
     * Reads a characteristic from the device
     */
    @SuppressLint("MissingPermission")
    private fun readCharacteristic(gatt: BluetoothGatt, characteristicUuid: UUID) {
        Log.d("DBG", "Attempting to read characteristic: $characteristicUuid")
        gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
            if (characteristic.isReadable()) {
                Handler(Looper.getMainLooper()).post {
                    val readSuccess = gatt.readCharacteristic(characteristic)
                    if (readSuccess) {
                        Log.d("DBG", "Read request sent successfully for characteristic: $characteristicUuid")
                    } else {
                        Log.e("DBG", "Failed to send read request for characteristic: $characteristicUuid")
                    }
                }
            } else {
                Log.e("DBG", "Characteristic $characteristicUuid is not readable")
            }
        } ?: Log.e("DBG", "Characteristic not found: $characteristicUuid")
    }

    /**
     * Writes a characteristic to the device with response
     */
    @SuppressLint("MissingPermission")
    private fun writeCharacteristicWithResponse(gatt: BluetoothGatt, characteristicUuid: UUID, payload: ByteArray) {
        Log.d("DBG", "Entered writeCharacteristicWithResponse")
        gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
            characteristic.value = payload
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            Handler(Looper.getMainLooper()).post {
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    /**
     * Writes a characteristic to the device without response
     */
    @SuppressLint("MissingPermission")
    private fun writeCharacteristicWithoutResponse(gatt: BluetoothGatt, characteristicUuid: UUID, payload: ByteArray) {
        Log.d("DBG", "Entered writeCharacteristicWithoutResponse")
        gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
            characteristic.value = payload
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            Handler(Looper.getMainLooper()).post {
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    /**
     * Handles successful connection to a device
     * @param device The device that has been connected
     */
    @SuppressLint("MissingPermission")
    private fun connected(device: Device) {
        scope.launch {
            device.setConnectionStatus(Device.ConnectionStatus.CONNECTED)

            Log.d("DBG", "Entered connected")
            Log.d("DBG", "Successfully connected to ${device.macAddress}")
            appConnState = "CONNECTED"

            ++numConnects
            Log.d("DBG", "    numConnects $numConnects numAttempts = $rcCnt")

            rcCnt = 0
            val bondstate = BondState.fromValue(device.bluetoothGatt.device.bondState)
            Log.d("DBG", "bondState = $bondstate")

            // Update or add device to both connectedDevices and allDevices lists
            _connectedDevices.update { devices ->
                val updatedDevices = devices.filter { it.macAddress != device.macAddress }
                updatedDevices + device
            }

            _allDevices.update { devices ->
                val updatedDevices = devices.map {
                    if (it.macAddress == device.macAddress) {
                        it.bluetoothGatt = device.bluetoothGatt
                        it.setConnectionStatus(Device.ConnectionStatus.CONNECTED)
                        it
                    } else it
                }
                if (!updatedDevices.any { it.macAddress == device.macAddress }) {
                    updatedDevices + device
                } else {
                    updatedDevices
                }
            }

            Log.d("DBG", "Device connected and added/updated in both lists. Connected devices: ${_connectedDevices.value.size}, All devices: ${_allDevices.value.size}")

            withContext(Dispatchers.Main) {
                device.bluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            }

            if (bondstate == BondState.NONE || bondstate == BondState.BONDED) {
                withContext(Dispatchers.Main) {
                    device.bluetoothGatt.discoverServices()
                }
            } else if (bondstate == BondState.BONDING) {
                Log.d("DBG", "BONDING IN PROCESSS NEED TO WAIT FOR COMPLETION BEFORE DISCOVER SERVICES")
            }
        }
    }

    /**
     * Handles device disconnection
     * @param prevConnectState The previous connection state
     * @param device The device that has been disconnected
     */
    private fun disconnected(prevConnectState: ConnState, device: Device) {
        scope.launch {
            Log.d("DBG", "Entered disconnected")
            Log.d("DBG", "Disconnected from ${device.macAddress}")
            updateDeviceSetupState(device, SetupState.DISCONNECTED)

            device.setConnectionStatus(Device.ConnectionStatus.DISCONNECTED)

            // Remove device from connectedDevices list
            _connectedDevices.update { it.filter { d -> d.macAddress != device.macAddress } }

            // Update the device's connection status in the allDevices list
            _allDevices.update { devices ->
                devices.map {
                    if (it.macAddress == device.macAddress) {
                        it.setConnectionStatus(Device.ConnectionStatus.DISCONNECTED)
                        it
                    } else it
                }
            }

            Log.d("DBG", "Device disconnected. Connected devices: ${_connectedDevices.value.size}, All devices: ${_allDevices.value.size}")
        }
    }

    /**
     * Completes the disconnection process for a device
     * @param device The device to complete disconnection... for
     */
    @SuppressLint("MissingPermission")
    private fun completeDisconnect(device: Device) {
        scope.launch {
            Log.d("DBG", "    Entered completeDisconnect")
            device.bluetoothGatt.close()
            device.setConnectionStatus(Device.ConnectionStatus.DISCONNECTED)

            // Update the device's connection status in the list of all devices
            _allDevices.update { devices ->
                devices.map {
                    if (it.macAddress == device.macAddress) {
                        it.setConnectionStatus(Device.ConnectionStatus.DISCONNECTED)
                        it
                    } else it
                }
            }
            appConnState = "DISCONNECTED"
            Log.d("DBG", "    Exited completeDisconnect")
        }
    }

    /**
     * Disconnects all BLE devices
     */
    @SuppressLint("MissingPermission")
    fun disconnectBle() {
        scope.launch {
            _connectedDevices.value.forEach { device ->
                appConnState = "DISCONNECTING"
                device.bluetoothGatt.disconnect()
                device.setConnectionStatus(Device.ConnectionStatus.DISCONNECTED)
            }

            // Update the connection status in allDevices list
            _allDevices.update { devices ->
                devices.map { device ->
                    if (_connectedDevices.value.any { it.macAddress == device.macAddress }) {
                        device.setConnectionStatus(Device.ConnectionStatus.DISCONNECTED)
                    }
                    device
                }
            }

            // Clear the connectedDevices list
            _connectedDevices.update { emptyList() }

            Log.d("DBG", "All devices disconnected. Connected devices: ${_connectedDevices.value.size}, All devices: ${_allDevices.value.size}")
        }
    }

    /**
     * Sanitizes a filename by replacing illegal characters and trimming length
     * @param filename The original filename to sanitize
     * @return The sanitized filename
     */
    fun sanitizeFilename(filename: String): String {
        Log.d("DBG", "Entering sanitizeFilename function")
        // Characters not allowed in filenames on Android: / \ : * ? " < > |
        val illegalCharacters = "[/\\\\:*?\"<> |]".toRegex()

        // Replace illegal characters with an underscore
        var sanitizedFilename = filename.replace(illegalCharacters, "_")
        Log.d("DBG", "Replaced illegal characters: $sanitizedFilename")

        // Trim whitespace and dots from the start and end of the filename
        sanitizedFilename = sanitizedFilename.trim().trimStart('.').trimEnd('.')
        Log.d("DBG", "Trimmed whitespace and dots: $sanitizedFilename")

        // Truncate the filename if it exceeds the maximum length (255 characters)
        if (sanitizedFilename.length > 255) {
            Log.d("DBG", "Filename exceeds maximum length, truncating")
            sanitizedFilename = sanitizedFilename.substring(0, 255)
        }

        Log.d("DBG", "Returning sanitized filename: $sanitizedFilename")
        return sanitizedFilename
    }

    /**
     * Updates the setup state of a device and performs actions based on the new state
     * @param device The device to update
     * @param newState The new setup state
     */
    private fun updateDeviceSetupState(device: Device, newState: SetupState) {
        Log.d("DBG", "Updating device ${device.macAddressString} state to $newState")
        device.setStatus(newState)
        when (newState) {
            SetupState.DISCONNECTED -> {
                scope.launch {
                    Log.d("DBG", "Device ${device.macAddressString} disconnected. Attempting to reconnect...")
                    delay(100)
                    val scanResult = ScanResult(
                        device.bluetoothGatt.device,
                        0, 0, 0, 0, 0, 0, 0, null, 0
                    )
                    connectBle(scanResult, isDelayed = false, isRetry = true)
                }
            }
            SetupState.CONNECTING -> {
                // No specific action needed, the connection process is handled in connectBle
            }
            SetupState.CONNECTED -> {
                // Services discovery will be initiated in the connection callback
            }
            SetupState.SERVICES_DISCOVERED -> {
                // MTU negotiation and enabling notifications are handled in onServicesDiscovered
            }
            SetupState.NOTIFICATIONS_ENABLED -> {
                if (device.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Aurelian) {
                    requestBatteryLevel(device.bluetoothGatt)
                } else {
                    requestBatteryLevel(device.bluetoothGatt)
                }
            }
            SetupState.BATTERY_RECEIVED -> {
                stopSamplingGattInitialSetup(device.bluetoothGatt)
            }
            SetupState.SAMPLING_STOPPED -> {
                if (device.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Aurelian) {
                    enablePreviewModeGatt(device.bluetoothGatt)
                } else {
                    requestFirmwareVersion(device.bluetoothGatt)
                }
            }
            SetupState.FIRMWARE_RECEIVED -> {
                if (device.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Aurelian) {
                    enablePreviewModeGatt(device.bluetoothGatt)
                } else if (device.deviceVersionInfo.argusVersion == 1) {
                    enablePreviewModeGatt(device.bluetoothGatt)
                } else {
                    requestNVM(device.bluetoothGatt)
                }
            }
            SetupState.NVM_RECEIVED -> {
                enablePreviewModeGatt(device.bluetoothGatt)
            }
            SetupState.PREVIEW_MODE_ENABLED -> {
                if (device.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Aurelian) {
                    enableSaveModeGatt(device.bluetoothGatt)
                } else {
                    sendTimestamp(device.bluetoothGatt)
                }
            }
            SetupState.TIMESTAMP_SENT -> {
                enableSaveModeGatt(device.bluetoothGatt)
            }
            SetupState.SAVE_MODE_ENABLED -> {
                Log.d("DBG", "Device ${device.macAddressString} setup completed successfully")
                updateDeviceSetupState(device, SetupState.SETUP_COMPLETE)
            }
            SetupState.SETUP_COMPLETE -> {
                Log.d("DBG", "Device ${device.macAddressString} is fully set up and ready to use")
                device.hasCompletedSetupBefore = true
                isOnboarding = false
                processOnboardingQueue()
            }
        }
    }

    /**
     * Adds a device to the onboarding queue
     * @param device The device to add to the queue
     */
    private fun addToOnboardingQueue(device: Device) {
        onboardingQueue.addLast(device)
        processOnboardingQueue()
    }

    /**
     * Processes the onboarding queue, setting up devices one at a time
     */
    private fun processOnboardingQueue() {
        if (isOnboarding || onboardingQueue.isEmpty()) return

        isOnboarding = true
        val device = onboardingQueue.removeFirst()
        updateDeviceSetupState(device, SetupState.CONNECTED)
    }

    /**
     * Stops sampling for all connected devices
     */
    @SuppressLint("MissingPermission")
    fun stopSamplingAllDevices() {
        scope.launch {
            Log.d("DBG", "Entered stopSamplingAllDevices")
            _connectedDevices.value.forEach { device ->
                val gatt = device.bluetoothGatt
                writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.STOP_SAMPLING)
                device.setIsStreaming(false)
                Log.d("DBG", "Stopped sampling for device ${device.macAddressString}")
            }
            Log.d("DBG", "Exited stopSamplingAllDevices")
        }
    }

    /**
     * Sends export flash data command to a specific device
     * @param device The device to send the command to
     */
    @SuppressLint("MissingPermission")
    fun sendExportFlashDataCommandDevice(device: Device) {
        scope.launch {
            Log.d("DBG", "Entered sendExportFlashDataCommandDevice")
            val gatt = device.bluetoothGatt
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.STOP_SAMPLING)
            device.setIsStreaming(false)
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.SEND_STORED_DATA)
            Log.d("DBG", "Exited sendExportFlashDataCommandDevice")
        }
    }

    /**
     * Sends clear flash command to a specific device
     * @param device The device to send the command to
     */
    @SuppressLint("MissingPermission")
    fun sendClearFlashCommandDevice(device: Device) {
        scope.launch {
            Log.d("DBG", "Entered sendClearFlashCommandDevice")
            val gatt = device.bluetoothGatt
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.CLEAR_FLASH)
            Log.d("DBG", "Exited sendClearFlashCommandDevice")
        }
    }

    /**
     * Enables save mode for a specific device
     * @param device The device to enable save mode for
     */
    @SuppressLint("MissingPermission")
    fun enableSaveModeDevice(device: Device) {
        scope.launch {
            Log.d("DBG", "Entered enableSaveModeDevice")
            val gatt = device.bluetoothGatt
            enableSaveModeGatt(gatt)
            Log.d("DBG", "Exited enableSaveModeDevice")
        }
    }

    /**
     * Starts sampling for a specific device
     * @param device The device to start sampling
     */
    @SuppressLint("MissingPermission")
    fun startSamplingDevice(device: Device) {
        scope.launch {
            Log.d("DBG", "Entered startSamplingDevice")
            val gatt = device.bluetoothGatt
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.START_SAMPLING)
            device.setIsStreaming(true)
            Log.d("DBG", "Exited startSamplingDevice")
        }
    }

    /**
     * Stops sampling for a specific device
     * @param device The device to stop sampling
     */
    @SuppressLint("MissingPermission")
    fun stopSamplingDevice(device: Device) {
        scope.launch {
            Log.d("DBG", "Entered stopSamplingDevice")
            val gatt = device.bluetoothGatt
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.STOP_SAMPLING)
            device.setIsStreaming(false)
            Log.d("DBG", "Exited stopSamplingDevice")
        }
    }

    /**
     * Starts sampling for a device using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    @SuppressLint("MissingPermission")
    fun startSamplingGatt(gatt: BluetoothGatt) {
        scope.launch {
            Log.d("DBG", "Entered startSamplingGatt")
            delay(200)
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.START_SAMPLING)
            Log.d("DBG", "Exited startSamplingGatt")
        }
    }

    /**
     * Stops sampling for a device using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    @SuppressLint("MissingPermission")
    fun stopSamplingGatt(gatt: BluetoothGatt) {
        scope.launch {
            Log.d("DBG", "Entered stopSamplingGatt")
            delay(200)
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.STOP_SAMPLING)
            Log.d("DBG", "Exited stopSamplingGatt")
        }
    }

    /**
     * Stops sampling for a device during initial setup using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    @SuppressLint("MissingPermission")
    fun stopSamplingGattInitialSetup(gatt: BluetoothGatt) {
        scope.launch {
            Log.d("DBG", "Entered stopSamplingGattInitialSetup")
            delay(200)
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, Commands.STOP_SAMPLING)
            Log.d("DBG", "Exited stopSamplingGattInitialSetup")

            val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
            if (device != null) {
                updateDeviceSetupState(device, SetupState.SAMPLING_STOPPED)
                Log.d("DBG", "Sampling stopped for device ${device.macAddressString}")
            } else {
                Log.e("DBG", "No device found for the given GATT connection")
            }
        }
    }

    /**
     * Retrieves a device by its ID
     * @param deviceId The ID of the device to retrieve
     * @return The Device object matching the given ID
     * @throws IllegalArgumentException if no device is found with the given ID
     */
    suspend fun getDeviceById(deviceId: String): Device {
        val devices = connectedDevices.first() // Collect the first (and only) value from the flow
        return devices.find { it.macAddressString == deviceId }
            ?: throw IllegalArgumentException("Device not found")
    }

    /**
     * Handles connection state change errors
     * @param status The HCI status of the connection
     * @param prevConnectState The previous connection state
     * @param newState The new connection state
     */
    @SuppressLint("MissingPermission")
    private fun connectionStateChangeError(status: HciStatus, prevConnectState: ConnState, newState: ConnState) {
        scope.launch {
            Log.d("DBG", "Entered connectionStateChangeError")
            Log.d("DBG", "  CONNECTION ERROR $status, prevConnectionState = $prevConnectState, newState = $newState")
            Log.d("DBG", "  appConnState = $appConnState ; connectState = $newState")
            Log.d("DBG", "rcCnt = $rcCnt rcCntMax = $rcCntMax")
            if (rcCnt == rcCntMax) {
                uitMessage("Rescan needed")
            }
            if (appConnState == "CONNECTING") {
                _connectedDevices.value.forEach { completeDisconnect(it) }
                if ((rcCnt < rcCntMax) && (status == HciStatus.ERROR_133) && (newState == ConnState.DISCONNECTED)) {
                    ++rcCnt
                    Log.d("DBG", "    Attempting automatic reconnection $rcCnt")
                    Log.d("DBG", "currentBleDevice = ${currentBleDevice.device.name}")
                    connectBle(currentBleDevice, true, isRetry = true)
                } else {
                    Log.d("DBG", "Exhausted reconnect attempts")
                }
            }
            if (appConnState == "CONNECTED") {
                _connectedDevices.value.forEach { completeDisconnect(it) }
                if ((rcCnt < rcCntMax) && newState == ConnState.DISCONNECTED && status == HciStatus.CONNECTION_TIMEOUT) {
                    ++rcCnt
                    Log.d("DBG", "    Attempting automatic reconnection after loss of signal $rcCnt")
                    Log.d("DBG", "currentBleDevice = ${currentBleDevice.device.name}")
                    connectBle(currentBleDevice, true, isRetry = true)
                } else {
                    Log.d("DBG", "Exhausted reconnect attempts after signal loss")
                }
            }
        }
    }

    /**
     * Extension function to find a characteristic by UUID in a BluetoothGatt
     * @param uuid The UUID of the characteristic to find
     * @return The BluetoothGattCharacteristic if found, null otherwise
     */
    private fun BluetoothGatt.findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        services?.forEach { service ->
            service.characteristics?.firstOrNull { characteristic ->
                characteristic.uuid == uuid
            }?.let { matchingCharacteristic ->
                return matchingCharacteristic
            }
        }
        return null
    }

    /**
     * Extension function to print the GATT table (services and characteristics) of a BluetoothGatt
     */
    fun BluetoothGatt.printGattTable() {
        if (services.isEmpty()) {
            Log.d("DBG", "No service and characteristic available, call discoverServices() first?")
            return
        }
        services.forEach { service ->
            val characteristicsTable = service.characteristics.joinToString(
                separator = "\n|--",
                prefix = "|--"
            ) { char ->
                var description = "${char.uuid}: ${char.printProperties()}"
                if (char.descriptors.isNotEmpty()) {
                    description += "\n" + char.descriptors.joinToString(
                        separator = "\n|------",
                        prefix = "|------"
                    ) { descriptor ->
                        "${descriptor.uuid}: ${descriptor.printProperties()}"
                    }
                }
                description
            }
            Log.i("DBG", "-\nService ${service.uuid}\nCharacteristics:\n$characteristicsTable")
        }
    }

    /**
     * Initiates MTU size negotiation with connected devices
     * @param mtuSizeRequest The desired MTU size
     */
    @SuppressLint("MissingPermission")
    fun negotiateMtuSize(mtuSizeRequest: Int) {
        scope.launch {
            Log.d("DBG", "Entered negotiateMtuSize")
            _connectedDevices.value.forEach { device ->
                withContext(Dispatchers.Main) {
                    device.bluetoothGatt.requestMtu(mtuSizeRequest)
                }
            }
            Log.d("DBG", "Exited negotiateMtuSize")
        }
    }

    /**
     * Extension function to print the properties of a BluetoothGattCharacteristic
     * @return A string representation of the characteristic's properties
     */
    private fun BluetoothGattCharacteristic.printProperties(): String = mutableListOf<String>().apply {
        if (isReadable()) add("READABLE")
        if (isWritable()) add("WRITABLE")
        if (isWritableWithoutResponse()) add("WRITABLE WITHOUT RESPONSE")
        if (isIndicatable()) add("INDICATABLE")
        if (isNotifiable()) add("NOTIFIABLE")
        if (isEmpty()) add("EMPTY")
    }.joinToString()

    /**
     * Extension function to check if a BluetoothGattCharacteristic is readable
     * @return True if the characteristic is readable, false otherwise
     */
    private fun BluetoothGattCharacteristic.isReadable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    /**
     * Extension function to check if a BluetoothGattCharacteristic is writable
     * @return True if the characteristic is writable, false otherwise
     */
    private fun BluetoothGattCharacteristic.isWritable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    /**
     * Extension function to check if a BluetoothGattCharacteristic is writable without response
     * @return True if the characteristic is writable without response, false otherwise
     */
    private fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    /**
     * Extension function to check if a BluetoothGattCharacteristic is indicatable
     * @return True if the characteristic is indicatable, false otherwise
     */
    private fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

    /**
     * Extension function to check if a BluetoothGattCharacteristic is notifiable
     * @return True if the characteristic is notifiable, false otherwise
     */
    private fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    /**
     * Extension function to check if a BluetoothGattCharacteristic contains a specific property
     * @param property The property to check for
     * @return True if the characteristic contains the property, false otherwise
     */
    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean =
        properties and property != 0

    /**
     * Extension function to print the properties of a BluetoothGattDescriptor
     * @return A string representation of the descriptor's properties
     */
    private fun BluetoothGattDescriptor.printProperties(): String = mutableListOf<String>().apply {
        if (isReadable()) add("READABLE")
        if (isWritable()) add("WRITABLE")
        if (isEmpty()) add("EMPTY")
    }.joinToString()

    /**
     * Extension function to check if a BluetoothGattDescriptor is readable
     * @return True if the descriptor is readable, false otherwise
     */
    private fun BluetoothGattDescriptor.isReadable(): Boolean =
        containsPermission(BluetoothGattDescriptor.PERMISSION_READ)

    /**
     * Extension function to check if a BluetoothGattDescriptor is writable
     * @return True if the descriptor is writable, false otherwise
     */
    private fun BluetoothGattDescriptor.isWritable(): Boolean =
        containsPermission(BluetoothGattDescriptor.PERMISSION_WRITE)

    /**
     * Extension function to check if a BluetoothGattDescriptor contains a specific permission
     * @param permission The permission to check for
     * @return True if the descriptor contains the permission, false otherwise
     */
    private fun BluetoothGattDescriptor.containsPermission(permission: Int): Boolean =
        permissions and permission != 0

    /**
     * Placeholder function for UI message handling
     * @param msg The message to display
     */
    private fun uitMessage(msg: String) {
        // TODO: Implement UI message handling if needed
    }

    /**
     * Forwards a status packet from BLE to the repository
     * @param statusPacket The status packet to forward
     */
    private fun fwdStatusBleToRepository(statusPacket: StatusPacket) {
        Log.d("DBG", "      BleManager - Entered fwdStatusBleToRepository")
        scope.launch {
            statusChannelServiceToRepository.trySend(statusPacket)
        }
        Log.d("DBG", "      BleManager - Exited fwdStatusBLeToRepository")
    }

    /**
     * Enables preview mode for a device using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    @SuppressLint("MissingPermission")
    fun enablePreviewModeGatt(gatt: BluetoothGatt) {
        scope.launch {
            Log.d("DBG", "Entered enablePreviewModeGatt")
            delay(200)
            writeCharacteristicWithoutResponse(gatt, CONFIG_UUID, Configurations.PREVIEW_MODE_ON)
            val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
            if (device != null) {
                updateDeviceSetupState(device, SetupState.PREVIEW_MODE_ENABLED)
                Log.d("DBG", "Preview mode enabled for device ${device.macAddressString}")
            } else {
                Log.e("DBG", "No device found for the given GATT connection")
            }
            Log.d("DBG", "Exited enablePreviewModeGatt")
        }
    }

    /**
     * Enables save mode for a device using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    @SuppressLint("MissingPermission")
    fun enableSaveModeGatt(gatt: BluetoothGatt) {
        scope.launch {
            Log.d("DBG", "Entered enableSaveModeGatt")
            delay(200)
            writeCharacteristicWithoutResponse(gatt, CONFIG_UUID, Configurations.SAVE_MODE_ON)
            val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
            if (device != null) {
                updateDeviceSetupState(device, SetupState.SAVE_MODE_ENABLED)
                Log.d("DBG", "Save mode enabled for device ${device.macAddressString}")
            } else {
                Log.e("DBG", "No device found for the given GATT connection")
            }
            Log.d("DBG", "Exited enableSaveModeGatt")
        }
    }

    /**
     * Sends a timestamp to a device using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    @SuppressLint("MissingPermission")
    fun sendTimestamp(gatt: BluetoothGatt) {
        scope.launch {
            Log.d("DBG", "Entered sendTimestamp")
            val timestamp = System.currentTimeMillis()
            val value = ByteArray(9)
            value[0] = 0x10.toByte()
            value[1] = ((timestamp shr 56) and 0xFF).toByte()
            value[2] = ((timestamp shr 48) and 0xFF).toByte()
            value[3] = ((timestamp shr 40) and 0xFF).toByte()
            value[4] = ((timestamp shr 32) and 0xFF).toByte()
            value[5] = ((timestamp shr 24) and 0xFF).toByte()
            value[6] = ((timestamp shr 16) and 0xFF).toByte()
            value[7] = ((timestamp shr 8) and 0xFF).toByte()
            value[8] = (timestamp and 0xFF).toByte()

            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, value)

            val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
            if (device != null) {
                updateDeviceSetupState(device, SetupState.TIMESTAMP_SENT)
                Log.d("DBG", "Timestamp sent to device ${device.macAddressString}")
            } else {
                Log.e("DBG", "No device found for the given GATT connection")
            }
            Log.d("DBG", "Exited sendTimestamp")
        }
    }

    /**
     * Requests the battery level from a device using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    @SuppressLint("MissingPermission")
    private fun requestBatteryLevel(gatt: BluetoothGatt) {
        scope.launch {
            Log.d("DBG", "Requesting battery level")
            val requestBattery = byteArrayOf(0x0B)
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, requestBattery)

            // Set a timeout in case we don't receive a response
            Handler(Looper.getMainLooper()).postDelayed({
                val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }
                if (device != null && device.status.value == SetupState.NOTIFICATIONS_ENABLED) {
                    Log.d("DBG", "Battery level not received after timeout. Moving to next state.")
                    updateDeviceSetupState(device, SetupState.BATTERY_RECEIVED)
                }
            }, 1000) // 1 second timeout
        }
    }

    /**
     * Requests the firmware version from a device using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    private fun requestFirmwareVersion(gatt: BluetoothGatt) {
        Log.d("DBG", "Requesting firmware version and battery level")
        val requestFirmwareVersion = byteArrayOf(0x06)
        val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }

        Handler(Looper.getMainLooper()).postDelayed({
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, requestFirmwareVersion)
        }, 200)

        // Move on if firmware is broken and not reporting its firmware version.
        Handler(Looper.getMainLooper()).postDelayed({
            if (device != null) {
                if (device.deviceVersionInfo.firmwareVersion == "-1") {
                    Log.d("DBG", "Firmware version not received, possible device is using development firmware. Moving to next state.")
                    updateDeviceSetupState(device, SetupState.FIRMWARE_RECEIVED)
                }
            }
        }, 1000)
    }

    /**
     * Requests the NVM (Non-Volatile Memory) version from a device using its GATT connection
     * @param gatt The BluetoothGatt connection to use
     */
    private fun requestNVM(gatt: BluetoothGatt) {
        Log.d("DBG", "Requesting NVM version")
        val requestNVM = byteArrayOf(0x0F)
        val device = _connectedDevices.value.find { it.bluetoothGatt == gatt }

        Handler(Looper.getMainLooper()).postDelayed({
            writeCharacteristicWithoutResponse(gatt, COMMAND_UUID, requestNVM)
        }, 200)

        Handler(Looper.getMainLooper()).postDelayed({
            if (device != null) {
                if (device.deviceVersionInfo.nvmVersion == 0) {
                    Log.d("DBG", "NVM version not received after attempt. Moving to next state.")
                    updateDeviceSetupState(device, SetupState.NVM_RECEIVED)
                }
            }
        }, 1000)
    }

    /**
     * Object containing command byte arrays for various device operations
     */
    private object Commands {
        val START_BLINK = byteArrayOf(0x00)
        val STOP_BLINK = byteArrayOf(0x01)
        val START_SAMPLING = byteArrayOf(0x02)
        val STOP_SAMPLING = byteArrayOf(0x03)
        val SEND_STORED_DATA = byteArrayOf(0x04)
        val REQUEST_FIRMWARE = byteArrayOf(0x06)
        val REQUEST_EMITTER_VALUES = byteArrayOf(0x07)
        val MARK_EVENT = byteArrayOf(0x08)
        val CLEAR_FLASH = byteArrayOf(0x09)
        val SEND_STATUS = byteArrayOf(0x0A)
        val REQUEST_BATTERY = byteArrayOf(0x0B)
        val BLE_OFF = byteArrayOf(0x0C)
        val AUTO_CALIBRATE = byteArrayOf(0x10)
        val SEND_FLASH_COUNT = byteArrayOf(0x11)
        val SEND_TIMESTAMP = byteArrayOf(0x10)
    }

    /**
     * Extension function to convert a ByteArray to a hexadecimal string
     * @return A string representation of the ByteArray in hexadecimal format
     */
    fun ByteArray.toHexString(): String =
        joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }

    /**
     * Object containing configuration byte arrays for various device modes
     */
    private object Configurations {
        const val SAVE_PATIENT_ID_START: Byte = 0x11
        val PREVIEW_MODE_ON = byteArrayOf(0x05, 0x01)
        val PREVIEW_MODE_OFF = byteArrayOf(0x05, 0x00)
        val SAVE_MODE_ON = byteArrayOf(0x09, 0x01)
        val SAVE_MODE_OFF = byteArrayOf(0x09, 0x00)
    }
}