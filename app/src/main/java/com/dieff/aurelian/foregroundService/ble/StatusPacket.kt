package com.dieff.aurelian.foregroundService.ble
// A StatusPacket is made up of:
// (1) an integer statusType field (constant integer types defined in BaseApp.kt)
// (2) a string statusValue field
data class StatusPacket(
    val statusType: Int,
    val statusValue: String
)