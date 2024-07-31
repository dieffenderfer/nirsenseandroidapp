package com.dieff.aurelian.foregroundService

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import android.widget.Toast
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
import com.dieff.aurelian.foregroundService.ble.ScanPacket
import com.dieff.aurelian.foregroundService.ble.StatusPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

// Moved outside of class because isBound will get set to false
// when Repository is re-instantiated.
var isBound: Boolean = false
    set(value) {
        Log.d("DBG","    Repository = isBound changed = $value")
        field = value
    }
var myServiceMessenger: Messenger? = null

val scanChannelRepositoryToViewModel = Channel<ScanPacket>(Channel.UNLIMITED)
val statusChannelRepositoryToViewModel = Channel<StatusPacket>(Channel.UNLIMITED)

class Repository (private val appContext: Context) {
    // Create scope for coroutine to run in
    private val scope = CoroutineScope(Dispatchers.IO)
//    Used for messages sent from MyService to Repository
    private val myMessenger = Messenger(IncomingHandler())
    var msgCount = 0
    init {
        Log.d("DBG", "    Repository - Entered init")
        // set up channel to pass status from Service to sharedViewModel
        fwdStatusFromServiceToViewModel()
        Log.d("DBG", "    Repository - Exited  init")
    }

    fun startAndBindService() {
        Log.d("DBG","    Repository - Entered startAndBindService")
        val serviceIntent = Intent(appContext, MyService::class.java)
        // Not needed but shown for now demonstrating we can pass data.
        serviceIntent.putExtra("inputExtra", "serviceStart")
        // startForegroundService() - Creates a background service, but the method
        // signals to the system that the service will promote itself to the foreground. Once
        // the service has been created, the service must call its startForeground() method
        // within five seconds. https://developer.android.com/guide/components/services#ExtendingService
        appContext.startForegroundService(serviceIntent)
        // Bind to service to communicate via Messenger.
        if (!isBound) {
            Log.d("DBG", "    Repository - BINDING to MyService")
            appContext.bindService(serviceIntent, myConnection, Context.BIND_IMPORTANT)
        }
        Log.d("DBG","    Repository - Exited  startAndBindService")
    }

    private val myConnection = object: ServiceConnection {
        // Called when service binds
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("DBG","    Repository - Entered onServiceConnected")
            myServiceMessenger = Messenger(service) // Create a messenger object from service IBinder
            isBound = true
            Log.d("DBG","    Repository - Exited  onServiceConnected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("DBG","    Repository - Entered onServiceDisconnected")
            myServiceMessenger = null
            isBound = false
            Log.d("DBG","    Repository - Exited  onServiceDisconnected")
        }
    }
    // Handle messages from MyService
    inner class IncomingHandler: Handler(Looper.getMainLooper() ){
        override fun handleMessage(msg: Message) {
            Log.d("DBG","    Repository - Entered remote handleMessage")
            // Identify message type
            when(msg.what) {
                MSG_TEST_MSG -> {
                    // This message passes a string bundle(KEY, VALUE)
                    val data = msg.data
                    val dataString = data.getString(MSG_KEY_MY_ARG1)
                    Log.d("DBG","Repository - $dataString")
                    Toast.makeText(appContext, dataString, Toast.LENGTH_SHORT).show()
                }
                // Other decodes go here
            }

            Log.d("DBG","    Repository - Exited  remote handleMessage")
        }
    }

    private fun sendMessage(cmdCode: Int, stringArg1: String = "", stringArg2: String = "",
                            boolArg: Boolean = false, longArg1: Long = 0, longArg2: Long = 0) {
        Log.d("DBG","    Repository - Entered sendMessage - isBound = $isBound")
        if (!isBound) return // ToDo: Might want to alert the user that the service is no longer bound
        // This field is queried by the service's when(msg.what) {...} statement.
        Log.d("DBG", "MyRepository - sendMessage enableAgeOutTimer = $boolArg ageOutTimerInterval = $longArg1 srEntryMaxAge = $longArg2")
        val msg = Message.obtain(null, cmdCode)
        when (cmdCode) {
            MSG_BT_CONNECT -> {
                val arg1Int = stringArg1.toInt()
                val bundle = Bundle()
                bundle.putInt("scanResultNdx", arg1Int)
                msg.data = bundle
            }
            MSG_BT_START_SCAN -> {
                val bundle = Bundle()
                bundle.putString(MSG_KEY_SCAN_FILTER_TYPE, stringArg1)
                bundle.putString(MSG_KEY_SCAN_FILTER_DEV_NAME, stringArg2)
                bundle.putBoolean(MSG_KEY_AGE_OUT_TIMER_ENABLE, boolArg)
                bundle.putLong(MSG_KEY_AGE_OUT_TIMER_INTERVAL, longArg1)
                bundle.putLong(MSG_KEY_AGE_OUT_MAX_ENTRY_AGE, longArg2)
                msg.data = bundle
            }
        }

/*        if (cmdCode == MSG_BT_CONNECT) {
            val arg1Int = stringArg1.toInt()
            val bundle = Bundle()
            bundle.putInt("scanResultNdx", arg1Int)
            msg.data = bundle
        } else if (cmdCode == MSG_BT_START_SCAN) {
            val bundle = Bundle()
            bundle.putString(MSG_KEY_SCAN_FILTER_TYPE, stringArg1)
            bundle.putString(MSG_KEY_SCAN_FILTER_DEV_NAME, stringArg2)
            bundle.putBoolean(MSG_KEY_AGE_OUT_TIMER_ENABLE, boolArg)
            bundle.putLong(MSG_KEY_AGE_OUT_TIMER_INTERVAL, longArg1)
            bundle.putLong(MSG_KEY_AGE_OUT_MAX_ENTRY_AGE, longArg2)
            msg.data = bundle
        }*/
        msg.replyTo = myMessenger // tell the service how to communicate back if needed.
        try {
            myServiceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.d("DBG","Error sending message")
            e.printStackTrace()
        }
        Log.d("DBG","    Repository - Exited  sendMessage - isBound = $isBound")
    }


    fun startScan(scanFilterType: String, scanFilterDevName: String, enableAgeOutTimer: Boolean,
                  ageOutTimerInterval: Long, srEntryMaxAge: Long) {
        Log.d("DBG","    Repository - Entered  startScan - isBound = $isBound")
        fwdScanResultFromServiceToViewModel()
        sendMessage(MSG_BT_START_SCAN, scanFilterType, scanFilterDevName, enableAgeOutTimer, ageOutTimerInterval, srEntryMaxAge)
        Log.d("DBG","    Repository - Exited   startScan - isBound = $isBound")
    }

    fun stopScan() {
        Log.d("DBG","    Repository - Entered  stopScan - isBound = $isBound")
        sendMessage(MSG_BT_STOP_SCAN)
        Log.d("DBG","    Repository - Exited   stopScan - isBound = $isBound")
    }

    fun bleConnect(scanResultNdx: Int) {
        Log.d("DBG","    Repository - Entered  bleConnect - isBound = $isBound")
        sendMessage(MSG_BT_CONNECT, scanResultNdx.toString())
        Log.d("DBG","    Repository - Exited   bleConnect - isBound = $isBound")
    }

    fun bleDisconnect() {
        Log.d("DBG","    Repository - Entered  bleDisconnect - isBound = $isBound")
        sendMessage(MSG_BT_DISCONNECT)
        Log.d("DBG","    Repository - Exited   bleDisconnect - isBound = $isBound")
    }

    @SuppressLint("MissingPermission")
    private fun fwdScanResultFromServiceToViewModel() {
        Log.d("DBG","    Repository - Entered receiveScanResult")
        scope.launch {
            for (scanPacket in scanChannelServiceToRepository) {  // Loops until channel is closed
                //Log.d("DBG","    Repository - IN - scanChannelServiceToRepository receiver")
                scanChannelRepositoryToViewModel.trySend(scanPacket)
            }
            Log.d("DBG","EXITED receive channel (Repository) loop")
        }
        Log.d("DBG","    Repository - Exited  receiveScanResult")
    }
    private fun fwdStatusFromServiceToViewModel() {
        Log.d("DBG","    Repository - Entered receiveScanResult")
        scope.launch {
            for (statusPacket in statusChannelServiceToRepository) {  // Loops until channel is closed
                //Log.d("DBG","    Repository - IN - statusChannelServiceToRepository receiver")
                statusChannelRepositoryToViewModel.trySend(statusPacket)
            }
            Log.d("DBG","EXITED receive channel (Repository) loop")
        }
        Log.d("DBG","    Repository - Exited  receiveScanResult")
    }
}