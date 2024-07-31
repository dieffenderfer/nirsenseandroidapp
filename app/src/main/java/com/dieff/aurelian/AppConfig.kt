package com.dieff.aurelian

//To configure which NIRSense system this companion app will support, set globalAppID.
object AppConfig {
    var appVersion: String = ""
    var appName: String = ""

    fun setAppDetails(appID: AppID) {
        when (appID) {
            AppID.AURELIAN_APP -> {
                appName = "Aurelian"
                appVersion = "1.2 alpha"
            }
            AppID.ARGUS_APP -> {
                appName = "Argus"
                appVersion = "2.0 alpha"
            }
            AppID.ANY_DEVICE_APP -> {
                appName = "Companion"
                appVersion = "1.0 alpha"
            }
        }
    }
}

// Enum class for all possible app IDs
enum class AppID {
    AURELIAN_APP,
    ARGUS_APP,
    ANY_DEVICE_APP
}

// Set this to configure the app as Argus or Aurelian
var globalAppID: AppID = AppID.ARGUS_APP
