package com.dieff.aurelian.ui.fragment


import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.dieff.aurelian.AppConfig
import com.dieff.aurelian.AppConfig.appName
import com.dieff.aurelian.AppConfig.appVersion
import com.dieff.aurelian.ui.viewmodel.SingleGraphViewModel
import com.github.mikephil.charting.charts.LineChart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.dieff.aurelian.databinding.FragmentSingleGraphBinding
import com.dieff.aurelian.foregroundService.ble.BleManager
import com.dieff.aurelian.foregroundService.ble.Device
import kotlinx.coroutines.delay

class SingleGraphFragment : Fragment() {

    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private lateinit var dismissButton: Button

    private val args: SingleGraphFragmentArgs by navArgs()
    private lateinit var currentDevice: Device

    private val singleGraphViewModel: SingleGraphViewModel by viewModels()

    private var _binding: FragmentSingleGraphBinding? = null
    private val binding get() = _binding!!

    private lateinit var lineChart: LineChart
    private lateinit var lineChart2: LineChart
    private lateinit var readoutBox1Middle: TextView
    private lateinit var readoutBox2Middle: TextView
    private lateinit var readoutBox3Middle: TextView
    private lateinit var readoutBox4Middle: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSingleGraphBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                currentDevice = BleManager.getDeviceById(args.deviceId)
                currentDevice.let { device ->
                    singleGraphViewModel.setDevice(device)
                    setupUI()
                    observeDeviceChanges()
                    setupClickListeners()
                }
            } catch (e: IllegalArgumentException) {
                // Handle the case where the device is not found
                Log.e("SingleGraphFragment", "Device not found", e)
                findNavController().popBackStack()
            }
        }
    }

    private fun setupUI() {
        // Set the app version text at the bottom
        val versionText = "NIRSense ${AppConfig.appName} Android App v${AppConfig.appVersion}"
        binding.versionTextView.text = versionText

        lineChart = binding.lineChart
        lineChart2 = binding.lineChart2
        readoutBox1Middle = binding.readoutBox1Middle
        readoutBox2Middle = binding.readoutBox2Middle
        readoutBox3Middle = binding.readoutBox3Middle
        readoutBox4Middle = binding.readoutBox4Middle

        progressBar = binding.progressBar
        progressText = binding.progressText

        progressBar.progress = 0
        progressBar.max = 100

        dismissButton = binding.btnDismiss

        dismissButton.visibility = View.GONE
        dismissButton.setOnClickListener {
            hideProgressInfo()
        }

        // Update readout labels and units
        binding.readoutBox1Top.text = singleGraphViewModel.readoutConfigs[0].label
        binding.readoutBox1Bottom.text = singleGraphViewModel.readoutConfigs[0].unit
        binding.readoutBox2Top.text = singleGraphViewModel.readoutConfigs[1].label
        binding.readoutBox2Bottom.text = singleGraphViewModel.readoutConfigs[1].unit
        binding.readoutBox3Top.text = singleGraphViewModel.readoutConfigs[2].label
        binding.readoutBox3Bottom.text = singleGraphViewModel.readoutConfigs[2].unit
        binding.readoutBox4Top.text = singleGraphViewModel.readoutConfigs[3].label
        binding.readoutBox4Bottom.text = singleGraphViewModel.readoutConfigs[3].unit

        singleGraphViewModel.setupLineChartDebug(lineChart, lineChart2)
        singleGraphViewModel.receivePacketArrayFromDataAggregator(lineChart, lineChart2, readoutBox1Middle, readoutBox2Middle, readoutBox3Middle, readoutBox4Middle)

        updateSamplingButtonText(currentDevice.isStreaming.value ?: false)
        updateProgressBar()
    }

    private fun observeDeviceChanges() {
        viewLifecycleOwner.lifecycleScope.launch {
            currentDevice.let { device ->
                launch {
                    device.isStreaming.collectLatest { isStreaming ->
                        updateSamplingButtonText(isStreaming)
                    }
                }
                launch {
                    device.totalPackets.collectLatest { _ ->
                        updateProgressBar()
                    }
                }
                launch {
                    device.currentPacketv2.collectLatest { _ ->
                        updateProgressBar()
                    }
                }
                launch {
                    device.connectionStatus.collectLatest { connectionStatus ->
                        updateStatusText(connectionStatus)
                    }
                }
                launch {
                    device.isDownloadComplete.collectLatest { isComplete ->
                        if (isComplete) {
                            delay(100)
                            onDownloadComplete(device)
                            // Reset the flag after handling
                            device.setDownloadComplete(false)
                        }
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnCaptureBounds.setOnClickListener {
            singleGraphViewModel.captureYBounds(lineChart)
        }

        binding.btnCaptureBounds2.setOnClickListener {
            singleGraphViewModel.captureYBounds(lineChart2)
        }

        binding.btnResetAutoscale.setOnClickListener {
            singleGraphViewModel.resetAutoscale(lineChart)
        }

        binding.btnResetAutoscale2.setOnClickListener {
            singleGraphViewModel.resetAutoscale(lineChart2)
        }

        binding.btnSampling.setOnClickListener {
            checkStreamingAndExecute {
                singleGraphViewModel.toggleSampling()
            }
        }

        binding.btnExport.setOnClickListener {
            checkStreamingAndExecute {
                showConfirmationDialog("Export Flash", "Are you ready to export the flash data from the device to the app?") {
                    singleGraphViewModel.exportFlashData()
                }
            }
        }

        binding.btnClear.setOnClickListener {
            checkStreamingAndExecute {
                showConfirmationDialog("Clear Flash", "Are you sure you want to clear the flash data on the device?\n\nThis action cannot be undone.") {
                    singleGraphViewModel.clearFlash()
                }
            }
        }

        binding.btnRemoveDevice.setOnClickListener {
            showRemoveDeviceConfirmationDialog()
        }
    }

    private fun showRemoveDeviceConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Disconnect Device")
            .setMessage("Are you sure you want to disconnect from ${currentDevice.name}?")
            .setPositiveButton("Disconnect") { _, _ ->
                removeDevice()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeDevice() {
        currentDevice.let { device ->
            BleManager.removeDevice(device)
            // Navigate back to the MultiGraphFragment
            //findNavController().popBackStack()
        }
    }

    private fun updateSamplingButtonText(isStreaming: Boolean) {
        binding.btnSampling.text = if (isStreaming) "Stop Sampling" else "Start Sampling"
    }

    private fun updateStatusText(connectionStatus: Device.ConnectionStatus) {
        val deviceName = currentDevice?.name ?: "Unknown Device"

        val statusText = when (connectionStatus) {
            Device.ConnectionStatus.CONNECTED -> "Connected"
            Device.ConnectionStatus.CONNECTING -> "Connecting..."
            Device.ConnectionStatus.DISCONNECTED -> "Disconnected"
        }

        val totalStatusText = "$deviceName – $statusText"
        val bulletPoint = " •"
        val spannableString = SpannableString(totalStatusText + bulletPoint)

        val bulletColor = when (connectionStatus) {
            Device.ConnectionStatus.CONNECTED -> Color.GREEN
            Device.ConnectionStatus.CONNECTING -> Color.rgb(255, 165, 0) // Orange
            Device.ConnectionStatus.DISCONNECTED -> Color.RED
        }

        spannableString.setSpan(
            ForegroundColorSpan(bulletColor),
            totalStatusText.length,
            totalStatusText.length + bulletPoint.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.txtTopTitle.text = spannableString
    }

    @SuppressLint("SetTextI18n")
    private fun updateProgressBar() {
        currentDevice.let { device ->
            Handler(Looper.getMainLooper()).post {
                if (device.totalPackets.value > 0) {
                    progressBar.visibility = View.VISIBLE
                    progressText.visibility = View.VISIBLE

                    progressBar.alpha = 1.0F
                    progressText.alpha = 1.0F

                    val progressPercent = if (device.totalPackets.value > 0)
                        (device.currentPacketv2.value * 100) / device.totalPackets.value
                    else 0

                    progressBar.progress = progressPercent
                    Log.d("DBG", "progressPercent = $progressPercent")

                    if (device.currentPacketv2.value >= device.totalPackets.value) {
                        // Transfer is complete
                        progressText.text = "Transfer is complete!\n\n${device.currentPacketv2.value} / ${device.totalPackets.value} ✅\n\nThe data is located at Documents/NIRSense/${device.historyFilename}.csv"
                        dismissButton.visibility = View.VISIBLE
                    } else {
                        progressText.text = "Stored data transfer is ${progressPercent}% complete.\n\n${device.currentPacketv2.value} / ${device.totalPackets.value}\n\nSaving data to Documents/NIRSense/${device.historyFilename}.csv"
                        dismissButton.visibility = View.GONE
                    }
                } else {
                    hideProgressInfo()
                }
            }
        }
    }

    private fun hideProgressInfo() {
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        dismissButton.visibility = View.GONE
    }

    private fun onDownloadComplete(device: Device) {
        binding.progressText.text = "Transfer is complete!\n\n${device.currentPacketv2.value} / ${device.totalPackets.value} ✅\n\nThe data is located at Documents/NIRSense/${device.historyFilename}.csv"

        dismissButton.visibility = View.VISIBLE

        // Play a sound effect
        try {
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mediaPlayer = MediaPlayer().apply {
                context?.let { setDataSource(it, defaultSoundUri) }
                setOnPreparedListener {
                    it.start()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkStreamingAndExecute(action: () -> Unit) {
        currentDevice.let { device ->
            if (device.isStreamingStoredData) {
                showAlertPopup("Warning", "Action cannot be performed while streaming stored data.")
            } else {
                action()
            }
        }
    }

    private fun showAlertPopup(title: String, message: String) {
        AlertDialog.Builder(requireContext()).apply {
            setTitle(title)
            setMessage(message)
            setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
        }.show()
    }

    private fun showConfirmationDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onConfirm() }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        singleGraphViewModel.cancelBufferMonitorJob()
        _binding = null
    }
}