package com.dieff.aurelian.ui.viewmodel


import android.util.Log
import androidx.lifecycle.ViewModel
import com.dieff.aurelian.APP_CONN_STATE
import com.dieff.aurelian.foregroundService.Repository
import com.dieff.aurelian.foregroundService.ble.Packet
import com.dieff.aurelian.foregroundService.ble.ScanPacket
import com.dieff.aurelian.globalAppContext
import com.dieff.aurelian.foregroundService.scanChannelRepositoryToViewModel
import com.dieff.aurelian.foregroundService.statusChannelRepositoryToViewModel
import com.dieff.aurelian.foregroundService.testVariable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

var isShareViewModelActive = false
val scanChannelViewModelToFragment = Channel<ScanPacket>(Channel.UNLIMITED)
val packetChannelDataAggregatorToFragment = Channel<Array<Packet?>>(Channel.UNLIMITED)


class SharedViewModel() : ViewModel() {

    private val myRepository = Repository(globalAppContext)

    // Create scope for coroutine to run in
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCleared() {
        super.onCleared()
        Log.d("DBG", "  SharedViewModel - Entered/Exited onCleared")
        // ToDo: Clear.close any scan channels here?
    }

/*    fun toastMsg(tmsg: String) {
        Toast.makeText(app, tmsg, Toast.LENGTH_SHORT).show()
    }*/
    fun setup() {
        Log.d("DBG", "  SharedViewModel - Entered setup")
        if (!isShareViewModelActive) {
            myRepository.startAndBindService()
            isShareViewModelActive = true
            receiveServiceStatus()
        }
        Log.d("DBG", "testVariable = $testVariable")
        Log.d("DBG", "  SharedViewModel - Exited  setup")
    }
    fun startScan(scanFilterType: String, scanFilterDevName: String,
                  enableAgeOutTimer: Boolean, ageOutTimerInterval: Long, srEntryMaxAge: Long) {
        Log.d("DBG","  SharedViewModel - Entered startScan")
        fwdScanResultsFromViewModelToBleFragment()
        myRepository.startScan(scanFilterType, scanFilterDevName, enableAgeOutTimer, ageOutTimerInterval, srEntryMaxAge)
        Log.d("DBG","  SharedViewModel - Exited  startScan")

    }
    private fun fwdScanResultsFromViewModelToBleFragment() {
        Log.d("DBG","  SharedViewModel - Entered receiveScanResult")
        scope.launch {
            for (scanPacket in scanChannelRepositoryToViewModel) {  // Loops until channel is closed
                scanChannelViewModelToFragment.trySend(scanPacket)
                Log.d("DBG","  SharedViewModel - IN - scanChannelViewModelToFragment receiver")
            }
            Log.d("DBG","EXITED receiveScanResultFromRepository (ViewModel) loop")
        }
        Log.d("DBG","  SharedViewModel - Exited  receiveScanResult")
    }

    private fun receiveServiceStatus() {
        Log.d("DBG","  SharedViewModel - Entered receiveServiceStatus")
        scope.launch {
            for (statusPacket in statusChannelRepositoryToViewModel) {  // Loops until channel is closed
                // Parse the received status
                when (statusPacket.statusType) {
                    APP_CONN_STATE -> {
                        Log.d("DBG", "  SharedViewModel - appConnState = ${statusPacket.statusValue}")
                    }
                    else -> Log.d("DBG", "SharedViewModel - unrecognized Service statusType ${statusPacket.statusType}")
                }
            }
            Log.d("DBG","EXITED receive Service status (sharedViewModel) loop")
        }
        Log.d("DBG","  SharedViewModel - Exited  receiveServiceStatus")
    }

    fun connectBle(scanResultNdx: Int) {
        Log.d("DBG","  SharedViewModel - Entered/Exited connectBle")

        myRepository.stopScan()
        myRepository.bleConnect(scanResultNdx)
        Log.d("DBG","  SharedViewModel - Entered/Exited connectBle")
    }

    fun disconnectBle(scanResultNdx: Int) {
        Log.d("DBG","  SharedViewModel - Entered/Exited disconnectBle")
        myRepository.bleDisconnect()
        Log.d("DBG","  SharedViewModel - Entered/Exited disconnectBle")
    }

    //Todo: Is there somewhere to set isShareViewModelActive to false if it is destroyed?
}