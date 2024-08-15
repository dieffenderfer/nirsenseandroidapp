package com.dieff.aurelian.ui.fragment

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
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.dieff.aurelian.AppConfig
import com.dieff.aurelian.databinding.FragmentSingleGraphBinding
import com.dieff.aurelian.foregroundService.ble.BleManager
import com.dieff.aurelian.foregroundService.ble.BleManager.sanitizeFilename
import com.dieff.aurelian.foregroundService.ble.Device
import com.dieff.aurelian.ui.viewmodel.SingleGraphViewModel
import com.github.mikephil.charting.charts.LineChart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SingleGraphFragment : Fragment() {

    private val args: SingleGraphFragmentArgs by navArgs()
    private lateinit var currentDevice: Device

    private val singleGraphViewModel: SingleGraphViewModel by viewModels()

    private var _binding: FragmentSingleGraphBinding? = null
    private val binding get() = _binding!!

    private var isEmbedded = false

    private lateinit var lineChart: LineChart
    private lateinit var lineChart2: LineChart
    private lateinit var readoutBox1Middle: TextView
    private lateinit var readoutBox2Middle: TextView
    private lateinit var readoutBox3Middle: TextView
    private lateinit var readoutBox4Middle: TextView

    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var dismissButton: Button

    private lateinit var animationDelaySlider: SeekBar
    private lateinit var animationDelayValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            isEmbedded = it.getBoolean(ARG_IS_EMBEDDED, false)
        }
    }

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
                    setupAnimationDelaySlider()
                }
            } catch (e: IllegalArgumentException) {
                Log.e("SingleGraphFragment", "Device not found", e)
                findNavController().popBackStack()
            }
        }
    }

    private fun setupUI() {
        if (isEmbedded) {
            adjustUIElementsForEmbed()
        }

        binding.apply {
            val versionText = "NIRSense ${AppConfig.appName} Android App v${AppConfig.appVersion}"
            versionTextView.text = versionText

            // Initialize chart and readout references
            this@SingleGraphFragment.lineChart = binding.lineChart
            this@SingleGraphFragment.lineChart2 = binding.lineChart2
            this@SingleGraphFragment.readoutBox1Middle = binding.readoutBox1Middle
            this@SingleGraphFragment.readoutBox2Middle = binding.readoutBox2Middle
            this@SingleGraphFragment.readoutBox3Middle = binding.readoutBox3Middle
            this@SingleGraphFragment.readoutBox4Middle = binding.readoutBox4Middle

            // Initialize progress-related views
            this@SingleGraphFragment.progressBar = binding.progressBar
            this@SingleGraphFragment.progressText = binding.progressText
            dismissButton = binding.btnDismiss

            progressBar.progress = 0
            progressBar.max = 100

            binding.apply {
                // Bind the TextViews for "Not EEG 1" and "Not EEG 2"
                val labelChart1 = binding.labelChart1
                val labelChart2 = binding.labelChart2
                val squareChart1 = binding.squareChart1
                val squareChart2 = binding.squareChart2
            }

            dismissButton.visibility = View.GONE
            dismissButton.setOnClickListener {
                hideProgressInfo()
            }

            // Update readout labels and units
            readoutBox1Top.text = singleGraphViewModel.readoutConfigs[0].label
            readoutBox1Bottom.text = singleGraphViewModel.readoutConfigs[0].unit
            readoutBox2Top.text = singleGraphViewModel.readoutConfigs[1].label
            readoutBox2Bottom.text = singleGraphViewModel.readoutConfigs[1].unit
            readoutBox3Top.text = singleGraphViewModel.readoutConfigs[2].label
            readoutBox3Bottom.text = singleGraphViewModel.readoutConfigs[2].unit
            readoutBox4Top.text = singleGraphViewModel.readoutConfigs[3].label
            readoutBox4Bottom.text = singleGraphViewModel.readoutConfigs[3].unit

            // Initialize animation delay slider and value
            this@SingleGraphFragment.animationDelaySlider = binding.animationDelaySlider
            this@SingleGraphFragment.animationDelayValue = binding.animationDelayValue
        }

        singleGraphViewModel.setupLineCharts(lineChart, lineChart2)
        singleGraphViewModel.receivePacketArrayFromDataAggregator(lineChart, lineChart2, readoutBox1Middle, readoutBox2Middle, readoutBox3Middle, readoutBox4Middle)

        updateSamplingButtonText(currentDevice.isStreaming.value ?: false)
        updateProgressBar()
    }

    private fun observeDeviceChanges() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    currentDevice.isStreaming.collect { isStreaming ->
                        updateSamplingButtonText(isStreaming)
                    }
                }
                launch {
                    currentDevice.totalPackets.collect { _ ->
                        updateProgressBar()
                    }
                }
                launch {
                    currentDevice.currentPacketv2.collect { _ ->
                        updateProgressBar()
                    }
                }
                launch {
                    currentDevice.connectionStatus.collect { connectionStatus ->
                        updateStatusText(connectionStatus)
                    }
                }
                launch {
                    currentDevice.isDownloadComplete.collect { isComplete ->
                        if (isComplete) {
                            delay(100)
                            onDownloadComplete(currentDevice)
                            currentDevice.setDownloadComplete(false)
                        }
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.apply {
            btnCaptureBounds.setOnClickListener {
                singleGraphViewModel.captureYBounds(lineChart)
            }

            btnCaptureBounds2.setOnClickListener {
                singleGraphViewModel.captureYBounds(lineChart2)
            }

            btnResetAutoscale.setOnClickListener {
                singleGraphViewModel.resetAutoscale(lineChart)
            }

            btnResetAutoscale2.setOnClickListener {
                singleGraphViewModel.resetAutoscale(lineChart2)
            }

            btnSampling.setOnClickListener {
                checkStreamingAndExecute {
                    singleGraphViewModel.toggleSampling()
                }
            }

            btnExport.setOnClickListener {
                checkStreamingAndExecute {
                    showConfirmationDialog("Export Flash", "Are you ready to export the flash data from the device to the app?") {
                        currentDevice.storedIndex = 0
                        val currentDateString = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
                        val newfilename = sanitizeFilename("${currentDevice.name}_${currentDateString}_stored")

                        currentDevice.historyFilename = newfilename
                        singleGraphViewModel.exportFlashData()
                    }
                }
            }

            btnClear.setOnClickListener {
                checkStreamingAndExecute {
                    showConfirmationDialog("Clear Flash", "Are you sure you want to clear the flash data on the device?\n\nThis action cannot be undone.") {
                        singleGraphViewModel.clearFlash()
                    }
                }
            }

            btnRemoveDevice.setOnClickListener {
                showRemoveDeviceConfirmationDialog()
            }
        }
    }

    private fun setupAnimationDelaySlider() {
        animationDelaySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val delay = progress + 1 // Ensure minimum delay of 1ms
                animationDelayValue.text = "$delay ms"
                singleGraphViewModel.setAnimationDelay(delay.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Set initial value
        val initialDelay = animationDelaySlider.progress + 1
        animationDelayValue.text = "$initialDelay ms"
        singleGraphViewModel.setAnimationDelay(initialDelay.toLong())
    }

    private fun showRemoveDeviceConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove Device")
            .setMessage("Are you sure you want to disconnect from ${currentDevice.name}?")
            .setPositiveButton("Remove") { _, _ ->
                removeDevice()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun adjustUIElementsForEmbed() {
        binding.apply {
            //Hide most buttons
            btnCaptureBounds.visibility = View.GONE
            btnCaptureBounds2.visibility = View.GONE
            btnResetAutoscale.visibility = View.GONE
            btnResetAutoscale2.visibility = View.GONE
            if (currentDevice.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Argus) {
                btnExport.visibility = View.GONE
                btnClear.visibility = View.GONE
            }

            //Hide app version text at the bottom
            binding.versionTextView.visibility = View.GONE

            //Probably not hiding these for long:
            binding.run {
                if (currentDevice.deviceVersionInfo.deviceFamily != Device.DeviceFamily.Aerie) {
                    lineChart2.visibility = View.GONE
                }
                digitalReadout.visibility = View.GONE
                // Hide the "Not EEG 1" and "Not EEG 2" labels
                labelChart1.visibility  = View.GONE
                labelChart2.visibility  = View.GONE
                squareChart1.visibility = View.GONE
                squareChart2.visibility = View.GONE
            }
        }
    }

    private fun removeDevice() {
        currentDevice.let { device ->
            BleManager.removeDevice(device)
            if (!isEmbedded) {
                findNavController().popBackStack()
            }
        }
    }

    private fun updateSamplingButtonText(isStreaming: Boolean) {
        _binding?.btnSampling?.text = if (isStreaming) "Stop Sampling" else "Start Sampling"
    }

    private fun updateStatusText(connectionStatus: Device.ConnectionStatus) {
        val deviceName = currentDevice.name?.removePrefix("NIRSense ") ?: "Unknown Device"
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

        _binding?.txtTopTitle?.text = spannableString
    }

    @SuppressLint("SetTextI18n")
    private fun updateProgressBar() {
        Handler(Looper.getMainLooper()).post {
            _binding?.apply {
                if (currentDevice.totalPackets.value > 0) {
                    progressBar.visibility = View.VISIBLE
                    progressText.visibility = View.VISIBLE

                    progressBar.alpha = 1.0F
                    progressText.alpha = 1.0F

                    val progressPercent = if (currentDevice.totalPackets.value > 0)
                        (currentDevice.currentPacketv2.value * 100) / currentDevice.totalPackets.value
                    else 0

                    progressBar.progress = progressPercent

                    if (currentDevice.currentPacketv2.value >= currentDevice.totalPackets.value) {
                        progressText.text = "Transfer is complete!\n\n${currentDevice.currentPacketv2.value} / ${currentDevice.totalPackets.value} ✅\n\nThe data is located at Documents/NIRSense/${currentDevice.historyFilename}.csv"
                        dismissButton.visibility = View.VISIBLE
                    } else {
                        progressText.text = "Stored data transfer is ${progressPercent}% complete.\n\n${currentDevice.currentPacketv2.value} / ${currentDevice.totalPackets.value}\n\nSaving data to Documents/NIRSense/${currentDevice.historyFilename}.csv"
                        dismissButton.visibility = View.GONE
                    }
                } else {
                    hideProgressInfo()
                }
            }
        }
    }

    private fun hideProgressInfo() {
        _binding?.apply {
            progressBar.visibility = View.GONE
            progressText.visibility = View.GONE
            dismissButton.visibility = View.GONE
        }
    }

    private fun onDownloadComplete(device: Device) {
        _binding?.apply {

            var transferText = ""
            if (device.currentPacketv2.value > 0) {
                transferText = "${device.currentPacketv2.value} packets downloaded. ✅"
            }

            progressText.text = "Transfer is complete!$transferText\n\nThe data is located at Documents/NIRSense/${device.historyFilename}.csv"
            dismissButton.visibility = View.VISIBLE
        }

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
        if (currentDevice.isStreamingStoredData) {
            showAlertPopup("Warning", "Action cannot be performed while streaming stored data.")
        } else {
            action()
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

    companion object {
        private const val ARG_IS_EMBEDDED = "is_embedded"

        fun newInstance(deviceId: String, isEmbedded: Boolean): SingleGraphFragment {
            return SingleGraphFragment().apply {
                arguments = Bundle().apply {
                    putString("deviceId", deviceId)
                    putBoolean(ARG_IS_EMBEDDED, isEmbedded)
                }
            }
        }
    }
}