package com.dieff.aurelian.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dieff.aurelian.R
import com.dieff.aurelian.databinding.FragmentMultiGraphBinding
import com.dieff.aurelian.foregroundService.ble.BleManager
import com.dieff.aurelian.foregroundService.ble.Device
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MultiGraphFragment : Fragment() {

    private var _binding: FragmentMultiGraphBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMultiGraphBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            BleManager.connectedDevices.collectLatest { devices ->
                updateDeviceButtons(devices)
            }
        }
    }

    private fun updateDeviceButtons(devices: List<Device>) {
        binding.deviceButtonContainer.removeAllViews()

        devices.forEach { device ->
            val button = Button(context).apply {
                text = device.name
                setOnClickListener {
                    navigateToSingleGraphFragment(device)
                }
            }
            binding.deviceButtonContainer.addView(button)
        }
    }

    private fun navigateToSingleGraphFragment(device: Device) {
        val action = MultiGraphFragmentDirections.actionMultiGraphFragmentToSingleGraphFragment(device.macAddressString)
        findNavController().navigate(action)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

