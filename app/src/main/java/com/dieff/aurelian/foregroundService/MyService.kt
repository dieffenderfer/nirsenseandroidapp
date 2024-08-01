package com.dieff.aurelian.foregroundService

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dieff.aurelian.AppConfig.appName
import com.dieff.aurelian.AppConfig.appVersion
import com.dieff.aurelian.R
import com.dieff.aurelian.BaseApp
import com.dieff.aurelian.MSG_BT_CONNECT
import com.dieff.aurelian.MSG_BT_DISCONNECT
import com.dieff.aurelian.MSG_BT_START_SCAN
import com.dieff.aurelian.MSG_BT_STOP_SCAN
import com.dieff.aurelian.MSG_KEY_AGE_OUT_MAX_ENTRY_AGE
import com.dieff.aurelian.MSG_KEY_AGE_OUT_TIMER_ENABLE
import com.dieff.aurelian.MSG_KEY_AGE_OUT_TIMER_INTERVAL
import com.dieff.aurelian.MSG_KEY_MY_ARG1
import com.dieff.aurelian.MSG_KEY_SCAN_FILTER_DEV_NAME
import com.dieff.aurelian.MSG_KEY_SCAN_FILTER_TYPE
import com.dieff.aurelian.MSG_TEST_MSG
import com.dieff.aurelian.PERSISTENT_NOTIFICATION_CHANNEL_ID
import com.dieff.aurelian.foregroundService.ble.BleManager
import com.dieff.aurelian.foregroundService.ble.ConnState
import com.dieff.aurelian.foregroundService.ble.MiniScanResult
import com.dieff.aurelian.foregroundService.ble.ScanPacket
import com.dieff.aurelian.foregroundService.ble.StatusPacket
import com.dieff.aurelian.ui.activity.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject

// Global variables
val scanChannelServiceToRepository = Channel<ScanPacket>(Channel.UNLIMITED)
// This channel can be used by the Service or BleManager to send status to the Repository
val statusChannelServiceToRepository = Channel<StatusPacket>(Channel.UNLIMITED)

var testVariable: String = "INIT_VALUE"
@AndroidEntryPoint
class MyService : Service() {
    @Inject
    lateinit var app: BaseApp
    private val scope = CoroutineScope(Dispatchers.IO)
    // ********************************************************************************************
    // ************** BLUETOOTH VALUES ************************************************************
    // ********************************************************************************************
    //
    // High level manager used to obtain an instance of an BluetoothAdapter and to conduct overall
    // Bluetooth Management.
    // The "by lazy" keywords tell the code to wait until the first use of bluetoothAdapter before
    // initializing via its lambda function {}.
    // The only bluetooth function in this module is scanning for devices. All other bluetooth
    // function is contained in BleManager.
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    // Instantiate the bleScanner
    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    // scan filter settings
    private var scanFilterType: String = ""
    private var scanFilterDevName: String = ""
    // manually track if scanning is on (no built-in function for this - sad)
    private var isScanning = false
    private var scanResultCount = 0
    private var isNewScanRequest = false

    // This list holds the time stamp (long) for a scanResult and
    // its index (int) in the scanResults list
    private var  timeAndNdxList = mutableListOf<Pair< Long,  Int>>()
    // mutable list to hold the ble scan results
    private val scanResults = mutableListOf<ScanResult>()
    // Instantiate the BleManager
    private val myBleManager = BleManager

    // ********************************************************************************************
    // ************** MESSENGER VALUES & INCOMING MESSAGE HANDLER FOR BOUND SERVICE ***************
    // ********************************************************************************************
    private val myMessenger = Messenger(IncomingHandler())
    var myClientMessenger: Messenger? = null
    // Handle messages from MyRepository
    inner class IncomingHandler: Handler(Looper.getMainLooper() ){
        override fun handleMessage(msg: Message) {
            Log.d("DBG","      MyService - Entered remote handleMessage")
            val data = msg.data
            // Identify message type
            when(msg.what) {
                MSG_BT_START_SCAN -> {
                    // Extract scan filter info from bundle
                    scanFilterType = data.getString(MSG_KEY_SCAN_FILTER_TYPE).toString()
                    Log.d("DBG", "MY_SERVICE from msg handler: scanFilterType = $scanFilterType")
                    scanFilterDevName = data.getString(MSG_KEY_SCAN_FILTER_DEV_NAME).toString()
                    val enableAgeOutTimer = data.getBoolean(MSG_KEY_AGE_OUT_TIMER_ENABLE)
                    val ageOutTimerInterval = data.getLong(MSG_KEY_AGE_OUT_TIMER_INTERVAL)
                    val srEntryMaxAge = data.getLong(MSG_KEY_AGE_OUT_MAX_ENTRY_AGE)
                    Log.d("DBG","MyService - enableAgeOutTimer = $enableAgeOutTimer ageOutTimerInterval = $ageOutTimerInterval srEntryMaxAge = $srEntryMaxAge")

                    if ((myBleManager.connectState == ConnState.UNKNOWNSTATECODE) or
                        (myBleManager.connectState == ConnState.DISCONNECTED)) {
                        startScan(enableAgeOutTimer, ageOutTimerInterval, srEntryMaxAge)
                    }
                }
                MSG_BT_STOP_SCAN -> {
                    stopScan()
                }
                MSG_BT_CONNECT -> {
                    val scanResultNdx = data.getInt("scanResultNdx")
                    myBleManager.connectBle(scanResults[scanResultNdx], isDelayed = true, isRetry = false)
                }
                MSG_BT_DISCONNECT -> {
                    myBleManager.disconnectBle()
                }
            }
            Log.d("DBG","      MyService - Exited  remote handleMessage")
        }
    }
    private fun sendMsgToClient(clientMsg: String?) {
        if (clientMsg == null) return
        val msg = Message.obtain(null, MSG_TEST_MSG)
        // Create a string bundle to pass to the service in (KEY,VALUE) pair.
        val bundle = Bundle()
        bundle.putString(MSG_KEY_MY_ARG1, "$clientMsg by Client")
        msg.data = bundle
        try {
            myClientMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.d("DBG","Error sending message")
            e.printStackTrace()
        }
    }

    // ********************************************************************************************
    // ************** SERVICE LIFECYCLE FUNCTIONS *************************************************
    // ********************************************************************************************
    override fun onCreate() {
        super.onCreate()
        Log.d("DBG","      MyService - Entered onCreate")
        enableForegroundService()
        myBleManager.connectState = ConnState.UNKNOWNSTATECODE
        Log.d("DBG", "      MyService - Exited  onCreate")
    }
    override fun onBind(intent: Intent?): IBinder? {
        Log.d("DBG","      MyService Entered/Exited onBind")
        return myMessenger.binder    }
    /* Runs each time the service receives a start command,
     in this case from startForegroundService operation.*/
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d("DBG", "      MyService - Entered onStartCommand ")

        //Toast.makeText(this, "Background service started", Toast.LENGTH_SHORT).show()

        Log.d("DBG", "      MyService - Exited  onStartCommand")

        return START_NOT_STICKY
    }
    private fun enableForegroundService() {
        Log.d("DBG", "      MyService - Entered enableForegroundService")
        // Create and post the persistent notification that keeps the foreground service alive.
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, PERSISTENT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NIRSense ${appName} ${appVersion}")
            .setContentText("Monitoring BLE Device for Packets")
            .setSmallIcon(R.drawable.ic_pstar)
            .setContentIntent(pendingIntent)
            .build()

        // without this statement, the service will be killed by the OS in 1 min.
        startForeground(1, notification)
        Log.d("DBG", "      MyService - Exited  enableForegroundService")

    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d("DBG","  MyService - Entered/Exited onDestroy")
        //Toast.makeText(app, "MyService destroyed", Toast.LENGTH_SHORT).show()
    }
    // This fun is called when the app is closed (swiped off the pending list);
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("DBG","      MyService - Entered onTaskRemoved")
        stopSelf() // This statement terminates the service otherwise the service keeps running.
        Log.d("DBG","      MyService - Exited  onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }
    // ********************************************************************************************
    // ************** BLUETOOTH FUNCTIONS *********************************************************
    // ********************************************************************************************
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        Log.d("DBG", "      MyService - Entered stopScan")
        cancelAgeOutTimer()
        // You can't execute startScan or stopScan unless scanning is in the opposite state;
        // otherwise the code can hang.
        if (isScanning) {
            Log.d("DBG","      MyService - stopScan command sent")
            bleScanner.stopScan(leScanCallback)
            isScanning = false
        }
        Log.d("DBG","      MyService - Exited  stopScan")
    }
    @SuppressLint("MissingPermission")
    private fun startScan(enableAgeOutTimer: Boolean, ageOutTimerInterval: Long, srEntryMaxAge: Long) {
        Log.d("DBG", "      MyService - Entered startScan")
        // You can't execute startScan or stopScan unless scanning is in the opposite state;
        // otherwise the code can hang.
        scanResultCount = 0
        isNewScanRequest = true
        if (!isScanning) {
            bleScanner.startScan(null, scanSettings, leScanCallback)
            if (enableAgeOutTimer) {
                startAgeOutTimer(ageOutTimerInterval, srEntryMaxAge)
            }
            isScanning = true
        }
        Log.d("DBG","      MyService - Exited  startScan")
    }

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d("DBG","      MyService - Entered ScanCallback $scanResultCount ${result.device.name} : ${result.device.address}")
            // *********************************************************************************
            // Filter the scan results
            // *********************************************************************************
            val devName: String =
                result.device.name?.toString() ?: "Unnamed"  // Hello Elvis operator :)
            if (filterScanResult(devName)) {
                ++scanResultCount
                manageScanLists(processScanNotAgeOut = true, newScanResult = result,
                    devName = devName, srEntryMaxAge = 0)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.d("DBG", "      MyService - Entered/Exited onScanFailed: code $errorCode\n")
        }
    }
    @SuppressLint("MissingPermission")
    @Synchronized // allow only one thread at a time to access the scan lists
    private fun manageScanLists(processScanNotAgeOut: Boolean, newScanResult: ScanResult? = null, devName: String = "", srEntryMaxAge: Long) {
        /*
                 **********************************************************************************
                 * Called by onScanResult when scanResult passes the scan filter test.
                 * - all inputs are required
                 * Called by the age-out timer when it elapses.
                 * - only processScanNotAgeOut = false required
                 * Manages scanResults list and the timeAndNdxList.
                 * scanResults is a mutable list of type scanResult.
                 * timeAndNdxList is a mutable list of pairs that track the time stamp of each scan result:
                 * list of pairs (timeStamp: Long, ndx: Int) where the ndx is the index into the
                 * scanResults list to link the time stamp to the scan result.
                 * processScanNotAgeOut:
                 * when true  - processing a new scan result; latestScanResult must be valid
                 * when false - being called by the entry age-out timer; latestScanResult will be null
                 * Sends a scanPacket back to MainFragment through Repository and SharedViewModel
                 ***********************************************************************************
        */
        val currentTimeStamp = SystemClock.elapsedRealtime()
        var staleSrNdx = 255
        var isStaleSrEntry = false
        var srEntryNdx = 255
        var updateNotAdd = true // init for common case
        var miniResult = MiniScanResult("NONE","NONE", 0)
        // get elapsed time from boot in milliseconds

        if (processScanNotAgeOut) { // process current scanResult
            // *****************************************************************
            // Determine if device already found or if it is newly discovered
            // *****************************************************************
            val matchNdx =
                scanResults.indexOfFirst {
                    it.device.address == newScanResult!!.device.address.toString()
                }
            if (matchNdx > -1) {
                // *****************************************************************
                // Device already found; update entry with new signal strength
                // *****************************************************************
                srEntryNdx = matchNdx
                scanResults[srEntryNdx] = newScanResult!!
                // Update the entry's timestamp
                val tnNdx = timeAndNdxList.indexOfFirst { it.second == srEntryNdx }
                if (tnNdx==-1) {
                    Log.d("doDBG", "      MyService - tnNdx = $tnNdx !!")
                }
                else {
                    timeAndNdxList[tnNdx] = Pair(currentTimeStamp, srEntryNdx)
                }
                // *****************************************************************
                // New device found, add it to the list
                // *****************************************************************
            } else {
                srEntryNdx = scanResults.size
                scanResults.add(srEntryNdx, newScanResult!!)
                updateNotAdd = false
                // Add a new entry in the timeAndNdxList
                timeAndNdxList.add(Pair(currentTimeStamp, srEntryNdx))
            }
            miniResult =
                MiniScanResult(devName,
                    newScanResult.device.address.toString(), newScanResult.rssi)
        } else { // age out the oldest entry if stale
            if (scanResults.size > 0) {
                // Sort by the timestamp (ascending)
                timeAndNdxList.sortBy { it.first } // put the smallest timestamp (oldest) in the first entry
                if (currentTimeStamp - timeAndNdxList[0].first > srEntryMaxAge) {
                    // Remove aged out scanResult
                    staleSrNdx = timeAndNdxList[0].second // save stale srNdx
                    timeAndNdxList.removeAt(0) // remove stale entry
                    // Repair timeAndNdxList srNdx values since one was removed
                    // Any savedSrNdx that is greater than the one removed must be decremented
                    // Don't want a "hole" in the savedSrNdx values
                    for (i in 0 until timeAndNdxList.size) {
                        val savedSrNdx = timeAndNdxList[i].second
                        if (savedSrNdx > staleSrNdx) {
                            var newPair = timeAndNdxList[i].copy(second = savedSrNdx - 1)
                            timeAndNdxList[i] = newPair
                        }
                    }
                    scanResults.removeAt(staleSrNdx)   // remove stale entry
                    isStaleSrEntry = true
                }
            }
        }
        // *********************************************************************************
        // Send back a scanPacket to HomeFragment through the Repository and SharedViewModel
        // *********************************************************************************
        if (processScanNotAgeOut or isStaleSrEntry) {
            val scanPacket = ScanPacket(
                processScanNotAgeOut,
                miniResult,
                updateNotAdd,
                srEntryNdx,
                scanResultCount,
                isStaleSrEntry,
                staleSrNdx
            )
            fwdScanResultServiceToRepository(scanPacket)
        }
    }
    private fun filterScanResult(scanResultDevName: String) : Boolean {
        when(scanFilterType) {
            "NONE" -> return true
            "EXACT_NAME" -> if(scanFilterDevName == scanResultDevName) return true
            "CONTAINS_NAME" -> if (scanResultDevName.contains(scanFilterDevName,true)) return true
        }
        return false
    }
    @Synchronized
    private fun fwdScanResultServiceToRepository(scanPacket: ScanPacket) {
        Log.d("DBG","      MyService - Entered fwdScanResultServiceToRepository")
        scope.launch {
            scanChannelServiceToRepository.trySend(scanPacket)
        }
        Log.d("DBG","      MyService - Exited  fwdScanResultServiceToRepository")
    }
    private fun fwdStatusServiceToRepository(statusPacket: StatusPacket) {
        Log.d("DBG","      MyService - Entered fwdStatusServiceToRepository")
        scope.launch {
            statusChannelServiceToRepository.trySend(statusPacket)
        }
        Log.d("DBG","      MyService - Exited  fwdStatusServiceToRepository")
    }

    private var ageOutJob : Job? = null
    private fun startAgeOutTimer(ageOutTimerInterval: Long, srEntryMaxAge: Long) {
        Log.d("DBG","Entered startAgeOutTimer BluetoothPeripheral.kt")
        cancelAgeOutTimer()
        ageOutJob = scope.launch {
            delay(ageOutTimerInterval)
            manageScanLists(processScanNotAgeOut = false, srEntryMaxAge = srEntryMaxAge)
            startAgeOutTimer(ageOutTimerInterval, srEntryMaxAge)
        }
        Log.d("DBG","Exited startAgeOutTimer BluetoothPeripheral.kt")
    }

    private fun cancelAgeOutTimer() {
        Log.d("DBG","MyService - Entered cancelAgeOutTimer BluetoothPeripheral.kt")
        if (ageOutJob != null) {
            ageOutJob?.cancel()
            ageOutJob = null
        }
        Log.d("DBG","MyService - Exited cancelAgeOutTimer BluetoothPeripheral.kt")
    }
}