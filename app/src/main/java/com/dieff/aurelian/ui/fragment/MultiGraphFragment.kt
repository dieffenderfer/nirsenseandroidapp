package com.dieff.aurelian.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dieff.aurelian.AppConfig
import com.dieff.aurelian.R
import com.dieff.aurelian.databinding.FragmentMultiGraphBinding
import com.dieff.aurelian.foregroundService.ble.BleManager
import com.dieff.aurelian.foregroundService.ble.Device
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MultiGraphFragment : Fragment() {
    private var _binding: FragmentMultiGraphBinding? = null
    private val binding get() = _binding!!

    private var isEmbedded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            isEmbedded = it.getBoolean(ARG_IS_EMBEDDED, false)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMultiGraphBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set app version text
        binding.versionTextView.text = "NIRSense ${AppConfig.appName} Android App v${AppConfig.appVersion}"

        // Hide the version text if the fragment is embedded
        if (isEmbedded) {
            binding.versionTextView.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            BleManager.allDevices.collectLatest { devices ->
                updateDeviceViews(devices.distinctBy { it.macAddressString }.take(6))
            }
        }
    }

    private fun updateDeviceViews(devices: List<Device>) {
        // Clear existing fragments
        childFragmentManager.fragments.forEach { fragment ->
            if (fragment is SingleGraphFragment) {
                childFragmentManager.beginTransaction().remove(fragment).commit()
            }
        }

        // Add new fragments
        devices.forEachIndexed { index, device ->
            val containerViewId = when (index) {
                0 -> R.id.graphContainer1
                1 -> R.id.graphContainer2
                2 -> R.id.graphContainer3
                3 -> R.id.graphContainer4
                4 -> R.id.graphContainer5
                5 -> R.id.graphContainer6
                else -> null
            }

            containerViewId?.let { viewId ->
                val singleGraphFragment = SingleGraphFragment.newInstance(device.macAddressString, true)
                childFragmentManager.beginTransaction()
                    .replace(viewId, singleGraphFragment)
                    .commit()

                binding.root.findViewById<View>(viewId).setOnClickListener {
                    navigateToSingleGraphFragment(device)
                }
            }
        }

        // Update visibility of containers
        for (i in 0 until 6) {
            val containerViewId = when (i) {
                0 -> R.id.graphContainer1
                1 -> R.id.graphContainer2
                2 -> R.id.graphContainer3
                3 -> R.id.graphContainer4
                4 -> R.id.graphContainer5
                5 -> R.id.graphContainer6
                else -> null
            }
            containerViewId?.let { viewId ->
                binding.root.findViewById<View>(viewId).visibility = if (i < devices.size) View.VISIBLE else View.GONE
            }
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

    companion object {
        private const val ARG_IS_EMBEDDED = "is_embedded"

        fun newInstance(isEmbedded: Boolean): MultiGraphFragment {
            return MultiGraphFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_EMBEDDED, isEmbedded)
                }
            }
        }
    }
}