# NIRSense Android App

## A Note for Developers
Before doing anything else, in Android Studio, set the Active Build Variant to `release`. This is crucial for performance!
To set the app configuration to Argus App, Aurelian App, or Companion App (any device), open `AppConfig.kt` and set the value of `var globalAppID` (see the enum class `AppID` for the options).

## Overview
The NIRSense Android App is a BLE-enabled app designed to connect with and manage NIRSense devices. It provides real-time data visualization and device control capabilities.

## Architecture
The app follows the MVVM (Model-View-ViewModel) architecture pattern and uses Android Jetpack components. It's designed to run as a foreground service to maintain persistent connections with BLE devices.

### Key Components:
- **BaseApp**: Application class that initializes global context and sets up notification channels.
- **MainActivity**: Container for fragments.
- **HomeFragment**: Main screen with device scanning functionality.
- **MultiGraphFragment**: Displays data from multiple connected devices.
- **SingleGraphFragment**: Detailed view for a single device with additional controls.
- **BleManager**: Singleton class managing BLE operations.
- **Device**: Represents a connected Bluetooth device and manages its data and state.