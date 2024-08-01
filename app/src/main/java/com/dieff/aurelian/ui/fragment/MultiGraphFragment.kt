package com.dieff.aurelian.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
            BleManager.allDevices.collectLatest { devices ->
                updateDeviceViews(devices)
            }
        }
    }

    private fun updateDeviceViews(devices: List<Device>) {
        binding.deviceContainer.removeAllViews()

        devices.forEach { device ->
            val deviceView = layoutInflater.inflate(R.layout.item_device_graph, binding.deviceContainer, false)

            val deviceNameView = deviceView.findViewById<TextView>(R.id.deviceName)
            val detailsButton = deviceView.findViewById<Button>(R.id.detailsButton)
            val graphContainer = deviceView.findViewById<LinearLayout>(R.id.graphContainer)

            deviceNameView.text = device.name
            detailsButton.setOnClickListener {
                navigateToSingleGraphFragment(device)
            }

            // Create and add SingleGraphFragment
            val singleGraphFragment = SingleGraphFragment().apply {
                arguments = Bundle().apply {
                    putString("deviceId", device.macAddressString)
                }
            }

            childFragmentManager.beginTransaction()
                .add(graphContainer.id, singleGraphFragment)
                .commit()

            binding.deviceContainer.addView(deviceView)
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