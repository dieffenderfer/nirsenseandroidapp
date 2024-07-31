package com.dieff.aurelian.foregroundService.ble
/**
 * This class describes the connection status codes as defined in the Android Developers Reference Library.
 * See https://developer.android.com/reference/android/bluetooth/BluetoothProfile -
 */
enum class ConnState(val value: Int) {
    /**
     * The profile is in the connected state
     */
    CONNECTED(0x02),
    /**
     * The profile is in the disconnected state
     */
    DISCONNECTED(0x00),
    /**
     * The profile is in the connecting state
     */
    CONNECTING(0x01),
    /**
     * The profile is in the disconnecting state
     */
    DISCONNECTING(0x03),
    /**
     * Used when status code is not defined in the class
     */
    UNKNOWNSTATECODE(0x04);

    companion object {
        fun fromValue(value: Int): ConnState {
            for (type in values()) {
                if (type.value == value) return type
            }
            return UNKNOWNSTATECODE
        }
    }
}