package com.dieff.aurelian.foregroundService.ble

data class ScanPacket(
    val isScanResult: Boolean,
    val scanResult: MiniScanResult,
    val updateNotAdd: Boolean,
    val listNdx: Int,
    val scanPacketCount: Int,
    val isStaleSrEntry: Boolean,
    val staleSrNdx: Int) {

}


