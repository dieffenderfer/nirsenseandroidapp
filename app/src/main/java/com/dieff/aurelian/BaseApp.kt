package com.dieff.aurelian

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

const val PERSISTENT_NOTIFICATION_CHANNEL_ID = "exampleServicePersistentChannel"
const val MESSAGE_NOTIFICATION_CHANNEL_ID_1 = "exampleMessageChannel1"

// Message types (accessed by msg.what)
const val MSG_TEST_MSG = 99
// message keys for MSG_TEST_MSG
const val MSG_KEY_MY_ARG1 = "91"

const val MSG_BT_START_SCAN = 1
// message keys for MSG_BT_START_SCAN
const val MSG_KEY_SCAN_FILTER_TYPE = "11"
const val MSG_KEY_SCAN_FILTER_DEV_NAME = "12"
const val MSG_KEY_AGE_OUT_TIMER_ENABLE = "13"
const val MSG_KEY_AGE_OUT_TIMER_INTERVAL = "14"
const val MSG_KEY_AGE_OUT_MAX_ENTRY_AGE = "15"

const val MSG_BT_STOP_SCAN = 2
const val MSG_BT_CONNECT = 3
const val MSG_BT_DISCONNECT = 4
// ***************************************************************
// keys for status channels
// ***************************************************************
const val APP_CONN_STATE = 0
/** UUID of the Client Characteristic Configuration Descriptor (0x2902) */
const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"

//const val MSG_REGISTER_CLIENT = 1
// Make the application context available to different components
// e.g. sharedViewModel
lateinit var globalAppContext: Context
// The onCreate of this class runs once when the app boots before our activities or fragments.
// Needs to be registered in the AndroidManifest.xml file in the first line after the
// opening application tag as-> android:name=".BaseApp"
@HiltAndroidApp
class BaseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("DBG","BaseApp - Entered onCreate")
        globalAppContext = applicationContext

        //Configure as ARGUS or AURELIAN here
        AppConfig.setAppDetails(globalAppID)

        // Since this application is built with a min level of 26 (Oreo noted as "o"),
        // we don't need to test to see if we're using the old way of implementing channels.
        // Define the channel
        val persistentServiceChannel = NotificationChannel(
            PERSISTENT_NOTIFICATION_CHANNEL_ID, "Example Persistent Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val messageServiceChannel = NotificationChannel(
            MESSAGE_NOTIFICATION_CHANNEL_ID_1, "Example Message Channel 1",
            NotificationManager.IMPORTANCE_DEFAULT)
        // Instantiate a notification manager.
        val manager = getSystemService(NotificationManager::class.java)
        // Create the service notification channels.
        manager.createNotificationChannel(persistentServiceChannel)
        manager.createNotificationChannel(messageServiceChannel)
        Log.d("DBG","BaseApp - Exited  onCreate")
    }
}
