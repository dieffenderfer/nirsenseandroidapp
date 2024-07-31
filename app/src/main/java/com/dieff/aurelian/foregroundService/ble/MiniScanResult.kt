package com.dieff.aurelian.foregroundService.ble

data class MiniScanResult(
    val bleDevName: String,
    val bleDevAddr: String,
    val bleDevRssi: Int
)
