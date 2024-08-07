package com.dieff.aurelian.ui.fragment

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Slide
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.dieff.aurelian.AppConfig.appName
import com.dieff.aurelian.AppConfig.appVersion
import com.dieff.aurelian.AppID
import com.dieff.aurelian.R
import com.dieff.aurelian.ui.adapter.ScanResultAdapter
import com.dieff.aurelian.ui.viewmodel.SharedViewModel
import com.dieff.aurelian.databinding.FragmentHomeBinding
import com.dieff.aurelian.foregroundService.ble.BleManager
import com.dieff.aurelian.foregroundService.ble.Device
import com.dieff.aurelian.foregroundService.ble.MiniScanResult
import com.dieff.aurelian.globalAppID
import com.dieff.aurelian.ui.viewmodel.scanChannelViewModelToFragment
import kotlinx.coroutines.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.addListener
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import kotlinx.coroutines.flow.collectLatest
import java.security.MessageDigest

/**
 * HomeFragment is the main screen of the application.
 * It handles device scanning, connection, and displays device status.
 */
@SuppressLint("SetTextI18n")
class HomeFragment : Fragment(), DeviceChangeListener {

    // Coroutine scope for asynchronous operations
    private val scope = CoroutineScope(Dispatchers.IO)

    // View binding
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel for communication between fragments
    private val sharedViewModel: SharedViewModel by activityViewModels()

    // UI elements
    private lateinit var statusTextView: TextView
    lateinit var scanPopupWindow: PopupWindow
    lateinit var scan_results_recycler_view: RecyclerView
    lateinit var textView_scanMsg: TextView
    private lateinit var connectButton: Button
    private lateinit var loadingAnimation: ImageView

    // List to store scan results
    private var miniScanResults = mutableListOf<MiniScanResult>()

    // Adapter for displaying scan results
    private val scanResultAdapter by lazy {
        ScanResultAdapter(
            miniScanResults,
            onSelectionChanged = { selectedPositions ->
                connectButton.isEnabled = selectedPositions.isNotEmpty()
            }
        )
    }

    // Flags for Bluetooth and permission states
    private var isBluetoothEnabled: Boolean = false
    private var isAccFineLocGranted: Boolean = false
    private var isBTScanGranted: Boolean = false
    private var isBTConnectGranted: Boolean = false
    private var isPostNotificationsGranted: Boolean = false

    // Continuations for handling asynchronous permission requests
    private var permissionContinuation: CancellableContinuation<Map<String, Boolean>>? = null
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            permissionContinuation?.resume(grantResults)
        }

    // Continuations for handling Bluetooth enable request
    private var bluetoothContinuation: Continuation<Boolean>? = null
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            bluetoothContinuation?.resume(true)
        } else {
            bluetoothContinuation?.resume(false)
        }
    }

    // Bluetooth adapter
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // Scan filter settings
    private var scanFilterType: String = "CONTAINS_NAME"
    private var scanFilterDevName: String = when (globalAppID) {
        AppID.AURELIAN_APP -> "NIRSENSE"
        AppID.ARGUS_APP -> "NIRSENSE ARGUS"
        AppID.ANY_DEVICE_APP -> "NIRSENSE"
    }

    // Job for observing device changes
    private var deviceObserverJob: Job? = null

    // Blur view for visual effects
    private lateinit var blurView: BlurView

    // Counter for permission request attempts
    private var permissionRequestCount: Int = 0

    // Flag to prevent multiple navigations to MultiGraphFragment
    private var hasNavigatedToMultiGraph = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("DBG","HomeFragment - Entered onCreate")
        sharedViewModel.setup()
        Log.d("DBG","HomeFragment - Exited onCreate")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        statusTextView = binding.statusTextView
        loadingAnimation = binding.loadingAnimation

        // Initialize and add blur view
        blurView = BlurView(requireContext())
        (binding.root as ViewGroup).addView(blurView, 0)
        blurView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        blurView.visibility = View.GONE

        // Observe connected devices and update UI accordingly
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BleManager.connectedDevices.collect { devices ->
                    updateStatusText(devices.size)
                    checkConnectedDevices(devices)
                }
            }
        }

        return binding.root
    }

    /**
     * Updates the status text during onboarding based on the number of connected devices
     */
    private fun updateStatusText(connectedDevicesCount: Int) {
        val numberStrings = arrayOf(
            "zero", "first", "second", "third", "fourth", "fifth",
            "sixth", "seventh", "eighth", "ninth", "tenth"
        )

        when (connectedDevicesCount) {
            0 -> statusTextView.text = "Please connect to a device.\n\nTap the Bluetooth Setup button to proceed."
            in 1..10 -> statusTextView.text = "Setting up the ${numberStrings[connectedDevicesCount]} device, please wait..."
            else -> statusTextView.text = "Connecting to $connectedDevicesCount devices..."
        }
    }

    private fun checkConnectedDevices(devices: List<Device>) {
        if (devices.isNotEmpty()) {
            // Handle connected devices
        } else {
            // Handle no connected devices
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("DBG", "HomeFragment - Entered onViewCreated")

        deviceChangeListener = this

        globalCurrentDevice?.let {
            onDeviceChanged(it)
        }

        statusTextView = binding.statusTextView

        binding.versionTextView.text = "NIRSense $appName Android App v$appVersion"

        setLogoImage()

        scanPopupWindow = PopupWindow(requireContext())
        val viewPopup = layoutInflater.inflate(R.layout.layout_popup, null)
        scanPopupWindow.contentView = viewPopup
        scanPopupWindow.isOutsideTouchable = true
        val slideIn = Slide()
        slideIn.slideEdge = Gravity.BOTTOM
        scanPopupWindow.enterTransition = slideIn
        val slideOut = Slide()
        slideOut.slideEdge = Gravity.BOTTOM
        scanPopupWindow.exitTransition = slideOut
        scan_results_recycler_view = viewPopup.findViewById(R.id.scan_results_recycler_view)
        textView_scanMsg = viewPopup.findViewById(R.id.textView_scanMsg)

        connectButton = viewPopup.findViewById(R.id.connectButton)
        connectButton.setOnClickListener {
            val selectedPositions = scanResultAdapter.getSelectedPositions()
            if (selectedPositions.isNotEmpty()) {
                scanPopupWindow.dismiss()

                if (selectedPositions.size == 1) {
                    statusTextView.text = "Connecting to the selected device, please wait..."
                } else {
                    statusTextView.text = "Connecting to the selected devices, please wait..."
                }
                connectToSelectedDevices(selectedPositions)
            } else {
                Toast.makeText(requireContext(), "Please select at least one device", Toast.LENGTH_SHORT).show()
            }
        }

        binding.scanButton.setOnClickListener {
            if (2 == 4) { //TODO FIX_ME remove, was: //(BleManager.connectedDevices.isNotEmpty()) {
                Toast.makeText(requireContext(), "A device is already connected, and this app only permits one connected device", Toast.LENGTH_LONG).show()
            } else {
                val havePermissions: Boolean = isBluetoothEnabled && isAccFineLocGranted && isBTScanGranted &&
                        isBTConnectGranted && isPostNotificationsGranted

                if (havePermissions) {
                    setupRecyclerView()
                    startBleScan()
                    binding.scanButton.isEnabled = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        binding.scanButton.isEnabled = true
                    }, 500)
                    showBlurredPopup()
                } else {
                    performAsyncSetup(requireActivity())
                }
            }
        }

        binding.messagesButton.setOnClickListener {
            val devices = BleManager.allDevices.value

            if (devices.isEmpty()) {
                Toast.makeText(context, "Please connect to at least one device.", Toast.LENGTH_SHORT).show()
            } else {
                val action = HomeFragmentDirections.actionHomeFragmentToMultiGraphFragment()
                findNavController().navigate(action)
                hasNavigatedToMultiGraph = true
            }
        }

        observeGlobalCurrentDevice()

        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                cleanupObservers()
                stopLoadingAnimation()
            }
        })

        Log.d("DBG", "HomeFragment - Exited onViewCreated")
    }

    override fun onResume() {
        super.onResume()
        Log.d("DBG", "HomeFragment - Entered onResume")
        hasNavigatedToMultiGraph = false
        observeGlobalCurrentDevice()
    }

    override fun onPause() {
        super.onPause()
        Log.d("DBG", "HomeFragment - Entered onPause")
        cleanupObservers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("DBG", "HomeFragment - Entered onDestroyView")
        cleanupObservers()
        stopLoadingAnimation()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("DBG", "HomeFragment - Entered onDestroy")
        cleanupObservers()
        stopLoadingAnimation()
    }

    private fun cleanupObservers() {
        deviceObserverJob?.cancel()
        deviceObserverJob = null
    }

    private fun observeGlobalCurrentDevice() {
        globalCurrentDevice?.let {
            observeDeviceChanges(it)
        }
    }

    private fun observeDeviceChanges(device: Device) {
        cleanupObservers()
        deviceObserverJob = viewLifecycleOwner.lifecycleScope.launch {
            device.status.collectLatest { status ->
                if (isAdded && view != null) {
                    handleDeviceStatusChange(status)
                }
            }
        }
    }

    private fun connectToSelectedDevices(selectedPositions: List<Int>) {
        showLoadingAnimation()
        hideButtons()

        //TODO improve this to not use delay.
        val delayTime: Long = 4000

        viewLifecycleOwner.lifecycleScope.launch {
            selectedPositions.forEachIndexed { index, position ->
                if (index > 0) {
                    delay(delayTime + (index*1000))

                }
                sharedViewModel.connectBle(position)
            }

            withTimeoutOrNull(900000) {
                BleManager.connectedDevices.collect { devices ->
                    if (devices.isNotEmpty() && !hasNavigatedToMultiGraph) {
                        delay(delayTime * selectedPositions.size) //TODO improve this to not use delay.
                        stopLoadingAnimation()
                        val action = HomeFragmentDirections.actionHomeFragmentToMultiGraphFragment()
                        findNavController().navigate(action)
                        hasNavigatedToMultiGraph = true
                        return@collect
                    }
                }
            }

            // If the timeout is reached without navigating
            stopLoadingAnimation()
            showButtons()
        }
    }

    private fun setLogoImage() {
        val logoImageView: ImageView = binding.nirsenseLogo

        when (globalAppID) {
            AppID.AURELIAN_APP -> logoImageView.setImageResource(R.drawable.aurelian_logo)
            AppID.ARGUS_APP -> logoImageView.setImageResource(R.drawable.argus_logo)
            AppID.ANY_DEVICE_APP -> logoImageView.setImageResource(R.drawable.nirsense_logo)
        }
    }

    private fun handleDeviceStatusChange(status: BleManager.SetupState) {
        Log.d("DBG", "handleDeviceStatusChange Device status changed: ${status.name}")

        globalCurrentDevice?.let { device ->
            val formattedState = BleManager.SetupState.getFormattedState(status)
            var statusText = "${device.name} status: $formattedState"

            if (status == BleManager.SetupState.SETUP_COMPLETE) {
                if (device.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Argus) {
                    statusText += "\n\n Device Family: ${device.deviceVersionInfo.deviceFamily}\n" +
                            "Argus Version: ${device.deviceVersionInfo.argusVersion}\n" +
                            "Firmware Version: ${device.deviceVersionInfo.firmwareVersion}\n" +
                            "NVM Version: ${device.deviceVersionInfo.nvmVersion}\n" +
                            "Live Data File: Documents/NIRSense/${device.filename}.csv\n"
                }
                else if (device.deviceVersionInfo.deviceFamily == Device.DeviceFamily.Aurelian) {
                    statusText += "\n\n Device Family: ${device.deviceVersionInfo.deviceFamily}\n" +
                            "Firmware Version: ${device.deviceVersionInfo.firmwareVersion}\n" +
                            "Live Data File: Documents/NIRSense/${device.filename}.csv\n"
                }
            }
            statusTextView.text = statusText
        }
    }

    override fun onDeviceChanged(device: Device) {
        Log.d("DBG", "onDeviceChanged: Device status changed: ${device.status.value}")
        if (isAdded) {
            observeDeviceChanges(device)
        }
    }

    private fun startBleScan() {
        Log.d("DBG","HomeFragment - Entered startBleScan")
        receiveScanResultFromViewModel()
        sharedViewModel.startScan(scanFilterType, scanFilterDevName,
            enableAgeOutTimer = false, ageOutTimerInterval = 3000, srEntryMaxAge = 1000)
        Log.d("DBG","HomeFragment - Exited startBleScan")
    }

    private fun setupRecyclerView() {
        scan_results_recycler_view.apply {
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
            isNestedScrollingEnabled = false
        }
        val animator = scan_results_recycler_view.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun receiveScanResultFromViewModel() {
        Log.d("DBG","HomeFragment - Entered receiveScanResult")
        scope.launch {
            for (scanPacket in scanChannelViewModelToFragment) {
                withContext(Dispatchers.Main) {
                    val ndx = scanPacket.listNdx
                    val scanResult = scanPacket.scanResult
                    if (scanPacket.isStaleSrEntry) {
                        miniScanResults.removeAt(scanPacket.staleSrNdx)
                        scanResultAdapter.notifyItemRemoved(scanPacket.staleSrNdx)
                    }
                    if (scanPacket.isScanResult) {
                        if (scanPacket.updateNotAdd) {
                            miniScanResults[ndx] = scanResult
                            scanResultAdapter.notifyItemChanged(scanPacket.listNdx)
                        } else {
                            miniScanResults.add(ndx, scanResult)
                            scanResultAdapter.notifyItemInserted(scanPacket.listNdx)
                        }
                    }
                }
            }
            Log.d("DBG","HomeFragment - EXITED receiveScanResultFromRepository (ViewModel) loop")
        }
        Log.d("DBG","HomeFragment - Exited receiveScanResult")
    }

    private fun performAsyncSetup(context: Context) {
        Log.d("DBG:","HomeFragment : Entered performAsyncSetup")
        lifecycleScope.launch {
            Log.d("DBG","performAsyncSetup Entered lifecycleScope.launch")

            var dialogResult = "Cancel"
            isAccFineLocGranted = isPermissionGranted(context, ACCESS_FINE_LOCATION)
            isBTScanGranted = isPermissionGranted(context, BLUETOOTH_SCAN)
            isBTConnectGranted = isPermissionGranted(context, BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isPostNotificationsGranted = isPermissionGranted(context, POST_NOTIFICATIONS)
            } else {
                isPostNotificationsGranted = true
            }
            val neededPermissions = mutableListOf<String>()
            if (!isAccFineLocGranted) { neededPermissions.add(ACCESS_FINE_LOCATION) }
            if (!isBTScanGranted) { neededPermissions.add(BLUETOOTH_SCAN) }
            if (!isBTConnectGranted) { neededPermissions.add(BLUETOOTH_CONNECT) }
            if (!isPostNotificationsGranted) { neededPermissions.add(POST_NOTIFICATIONS) }
            isBluetoothEnabled = bluetoothAdapter.isEnabled
            Log.d("DBG","Permissions: AFL = $isAccFineLocGranted BTS = $isBTScanGranted BTC = $isBTConnectGranted PN = $isPostNotificationsGranted" )
            if (neededPermissions.isNotEmpty()) {
                Log.d("DBG","Entered neededPermissions.isNotEmpty block: permissionRequestCount = $permissionRequestCount")
                if (permissionRequestCount > 1) {
                    displayOneButtonDialogNoResult(
                        "WARNING - TOO MANY DENIALS",
                        "Permissions must be added via app settings",
                        "Ok"
                    )
                } else {
                    if (permissionRequestCount == 0) {
                        dialogResult = displayTwoButtonDialog(
                            "Rationale for Permissions",
                            "We need these permissions for Bluetooth functionality", "Ok", "Cancel"
                        )
                        Log.d("DBG","Dialog: Rational result = $dialogResult")
                    }
                    if (permissionRequestCount == 1) {
                        dialogResult = displayTwoButtonDialog(
                            "WARNING",
                            "We need these permissions for Bluetooth functionality", "Ok", "Cancel"
                        )
                        Log.d("DBG","Dialog: WARNING result = $dialogResult")
                    }
                    if (dialogResult == "Ok") {
                        val grantedPermissions = requestMultiplePermissions(neededPermissions)
                        permissionRequestCount++
                        if (neededPermissions.contains(ACCESS_FINE_LOCATION)) {
                            isAccFineLocGranted = grantedPermissions[ACCESS_FINE_LOCATION] ?: false
                        }
                        if (neededPermissions.contains(BLUETOOTH_SCAN)) {
                            isBTScanGranted = grantedPermissions[BLUETOOTH_SCAN] ?: false
                        }
                        if (neededPermissions.contains(BLUETOOTH_CONNECT)) {
                            isBTConnectGranted = grantedPermissions[BLUETOOTH_CONNECT] ?: false
                        }
                        if (neededPermissions.contains(POST_NOTIFICATIONS)) {
                            isPostNotificationsGranted =
                                grantedPermissions[POST_NOTIFICATIONS] ?: false
                        }
                    }
                }
            }
            Log.d("DBG","AFL = $isAccFineLocGranted BTS = $isBTScanGranted BTC = $isBTConnectGranted PN = $isPostNotificationsGranted BTE = $isBluetoothEnabled")
            if (!isBluetoothEnabled && isBTConnectGranted) {
                isBluetoothEnabled = suspendCancellableCoroutine { continuation ->
                    bluetoothContinuation = continuation
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                }
                if (!isBluetoothEnabled) {
                    Log.d("DBG","USER DID NOT ENABLE BT")
                } else {
                    Log.d("DBG","USER ENABLED BT")
                }
            }
            if (isBluetoothEnabled && isBTScanGranted && isBTConnectGranted && isAccFineLocGranted && isPostNotificationsGranted) {
                binding.scanButton.performClick()
            }
            Log.d("DBG","performAsyncSetup Exited lifecycleScope.launch")
        }
        Log.d("DBG:","HomeFragment : Exited performAsyncSetup")
    }

    private suspend fun displayTwoButtonDialog(title: String, message: String, posButtonTxt: String, negButtonTxt: String): String {
        return suspendCoroutine { continuation ->
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(posButtonTxt) { _, _ ->
                    continuation.resume(posButtonTxt)
                }
                .setNegativeButton(negButtonTxt) { _, _ ->
                    continuation.resume(negButtonTxt)
                }
                .setOnCancelListener {
                    continuation.resume("Cancel")
                }
                .create()
            dialog.show()
        }
    }

    private suspend fun displayOneButtonDialogNoResult(title: String, message: String, posButtonTxt: String) {
        suspendCoroutine<Unit> { continuation ->
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(posButtonTxt) { _, _ ->
                    continuation.resume(Unit)
                }
                .setOnCancelListener {
                    continuation.resume(Unit)
                }
                .create()
            dialog.show()
        }
    }

    private fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun requestMultiplePermissions(permissions: List<String>): Map<String, Boolean> {
        return suspendCancellableCoroutine { continuation: CancellableContinuation<Map<String, Boolean>> ->
            permissionContinuation = continuation
            requestMultiplePermissionsLauncher.launch(permissions.toTypedArray())
            continuation.invokeOnCancellation {
                permissionContinuation = null
            }
        }
    }

    private fun showBlurredPopup() {
        animateBlurView()
        scanPopupWindow.showAtLocation(binding.scanButton, Gravity.CENTER, 0, 0)
    }

    private fun animateBlurView() {
        blurView.alpha = 0f
        blurView.visibility = View.VISIBLE

        ValueAnimator.ofFloat(0f, 25f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                blurView.setBlurRadius(animator.animatedValue as Float)
                blurView.alpha = (animator.animatedValue as Float) / 25f
            }
            start()
        }
    }

    private fun animateBlurViewOut() {
        ValueAnimator.ofFloat(25f, 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                blurView.setBlurRadius(animator.animatedValue as Float)
                blurView.alpha = (animator.animatedValue as Float) / 25f
            }
            addListener(onEnd = {
                blurView.visibility = View.GONE
            })
            start()
        }
    }

    private fun showLoadingAnimation() {
        val requestOptions = RequestOptions().transform(AlphaTransformation(0.1f))

        Glide.with(this)
            .asGif()
            .load(R.drawable.animation)
            .apply(requestOptions)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(loadingAnimation)

        loadingAnimation.visibility = View.VISIBLE
    }

    private fun stopLoadingAnimation() {
        Glide.with(this).clear(loadingAnimation)
        loadingAnimation.visibility = View.GONE
    }

    private fun hideButtons() {
        binding.scanButton.visibility = View.GONE
        binding.messagesButton.visibility = View.GONE
    }

    private fun showButtons() {
        binding.scanButton.visibility = View.VISIBLE
        binding.messagesButton.visibility = View.VISIBLE
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Glide.get(requireContext()).clearMemory()
    }
}

interface DeviceChangeListener {
    fun onDeviceChanged(device: Device)
}

var globalCurrentDevice: Device? = null
    set(value) {
        field = value
        value?.let {
            deviceChangeListener?.onDeviceChanged(it)
        }
    }

var deviceChangeListener: DeviceChangeListener? = null

class AlphaTransformation(private val alpha: Float) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val result = pool.get(toTransform.width, toTransform.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        paint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(toTransform, 0f, 0f, paint)
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(("alpha_transformation$alpha").toByteArray())
    }

    override fun equals(other: Any?): Boolean {
        return other is AlphaTransformation && other.alpha == alpha
    }

    override fun hashCode(): Int {
        return alpha.hashCode()
    }
}

class BlurView(context: Context) : View(context) {
    private val paint = Paint()
    private var blurRadius = 0f
    private val rect = android.graphics.Rect()

    init {
        paint.color = ContextCompat.getColor(context, android.R.color.white)
    }

    fun setBlurRadius(radius: Float) {
        blurRadius = radius
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.getClipBounds(rect)
        canvas.saveLayer(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), null)
        paint.alpha = (blurRadius * 10).toInt().coerceIn(0, 255)
        canvas.drawColor(paint.color)
        canvas.restore()
    }
}