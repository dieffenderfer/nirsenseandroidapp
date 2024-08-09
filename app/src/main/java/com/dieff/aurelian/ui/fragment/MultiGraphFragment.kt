package com.dieff.aurelian.ui.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMultiGraphBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.versionTextView.text = "NIRSense ${AppConfig.appName} Android App v${AppConfig.appVersion}"

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
                childFragmentManager.beginTransaction().remove(fragment).commitNow()
            }
        }

        // Hide all CardViews initially
        listOf(binding.cardView1, binding.cardView2, binding.cardView3,
            binding.cardView4, binding.cardView5, binding.cardView6).forEach {
            it.visibility = View.GONE
        }

        // Add new fragments and show corresponding CardViews
        devices.forEachIndexed { index, device ->
            val cardViewAndContainerId = when (index) {
                0 -> binding.cardView1 to R.id.graphContainer1
                1 -> binding.cardView2 to R.id.graphContainer2
                2 -> binding.cardView3 to R.id.graphContainer3
                3 -> binding.cardView4 to R.id.graphContainer4
                4 -> binding.cardView5 to R.id.graphContainer5
                5 -> binding.cardView6 to R.id.graphContainer6
                else -> null
            }

            cardViewAndContainerId?.let { (cardView, containerId) ->
                cardView.visibility = View.VISIBLE
                val singleGraphFragment = SingleGraphFragment.newInstance(device.macAddressString, true)
                childFragmentManager.beginTransaction()
                    .replace(containerId, singleGraphFragment)
                    .commitNow()

                cardView.setOnClickListener {
                    navigateToSingleGraphFragment(device)
                }
            }
        }

        // Update layout constraints based on the number of visible CardViews
        updateLayoutConstraints(devices.size)
    }

    private fun updateLayoutConstraints(visibleCount: Int) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(binding.root as ConstraintLayout)

        when (visibleCount) {
            1 -> setupOneCardLayout(constraintSet)
            2 -> setupTwoCardLayout(constraintSet)
            3 -> setupThreeCardLayout(constraintSet)
            4 -> setupFourCardLayout(constraintSet)
            5 -> setupFiveCardLayout(constraintSet)
            6 -> setupSixCardLayout(constraintSet)
        }

        constraintSet.applyTo(binding.root as ConstraintLayout)
    }

    private fun setupOneCardLayout(constraintSet: ConstraintSet) {
        constraintSet.connect(R.id.cardView1, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        constraintSet.connect(R.id.cardView1, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView1, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraintSet.connect(R.id.cardView1, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
    }

    private fun setupTwoCardLayout(constraintSet: ConstraintSet) {
        setupOneCardLayout(constraintSet)
        constraintSet.connect(R.id.cardView1, ConstraintSet.BOTTOM, R.id.cardView2, ConstraintSet.TOP)
        constraintSet.connect(R.id.cardView2, ConstraintSet.TOP, R.id.cardView1, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView2, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView2, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraintSet.connect(R.id.cardView2, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
    }

    private fun setupThreeCardLayout(constraintSet: ConstraintSet) {
        setupTwoCardLayout(constraintSet)
        constraintSet.connect(R.id.cardView2, ConstraintSet.END, R.id.cardView3, ConstraintSet.START)
        constraintSet.connect(R.id.cardView3, ConstraintSet.TOP, R.id.cardView1, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView3, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView3, ConstraintSet.START, R.id.cardView2, ConstraintSet.END)
        constraintSet.connect(R.id.cardView3, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
    }

    private fun setupFourCardLayout(constraintSet: ConstraintSet) {
        setupTwoCardLayout(constraintSet)
        constraintSet.connect(R.id.cardView1, ConstraintSet.END, R.id.cardView4, ConstraintSet.START)
        constraintSet.connect(R.id.cardView2, ConstraintSet.END, R.id.cardView3, ConstraintSet.START)
        constraintSet.connect(R.id.cardView3, ConstraintSet.TOP, R.id.cardView4, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView3, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView3, ConstraintSet.START, R.id.cardView2, ConstraintSet.END)
        constraintSet.connect(R.id.cardView3, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        constraintSet.connect(R.id.cardView4, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        constraintSet.connect(R.id.cardView4, ConstraintSet.BOTTOM, R.id.cardView3, ConstraintSet.TOP)
        constraintSet.connect(R.id.cardView4, ConstraintSet.START, R.id.cardView1, ConstraintSet.END)
        constraintSet.connect(R.id.cardView4, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
    }

    private fun setupFiveCardLayout(constraintSet: ConstraintSet) {
        setupThreeCardLayout(constraintSet)
        constraintSet.connect(R.id.cardView2, ConstraintSet.BOTTOM, R.id.cardView4, ConstraintSet.TOP)
        constraintSet.connect(R.id.cardView3, ConstraintSet.BOTTOM, R.id.cardView5, ConstraintSet.TOP)
        constraintSet.connect(R.id.cardView4, ConstraintSet.TOP, R.id.cardView2, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView4, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView4, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraintSet.connect(R.id.cardView4, ConstraintSet.END, R.id.cardView5, ConstraintSet.START)
        constraintSet.connect(R.id.cardView5, ConstraintSet.TOP, R.id.cardView3, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView5, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView5, ConstraintSet.START, R.id.cardView4, ConstraintSet.END)
        constraintSet.connect(R.id.cardView5, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
    }

    private fun setupSixCardLayout(constraintSet: ConstraintSet) {
        setupFourCardLayout(constraintSet)
        constraintSet.connect(R.id.cardView3, ConstraintSet.BOTTOM, R.id.cardView5, ConstraintSet.TOP)
        constraintSet.connect(R.id.cardView4, ConstraintSet.BOTTOM, R.id.cardView6, ConstraintSet.TOP)
        constraintSet.connect(R.id.cardView5, ConstraintSet.TOP, R.id.cardView3, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView5, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView5, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraintSet.connect(R.id.cardView5, ConstraintSet.END, R.id.cardView6, ConstraintSet.START)
        constraintSet.connect(R.id.cardView6, ConstraintSet.TOP, R.id.cardView4, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView6, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.cardView6, ConstraintSet.START, R.id.cardView5, ConstraintSet.END)
        constraintSet.connect(R.id.cardView6, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
    }

    private fun navigateToSingleGraphFragment(device: Device) {
        try {
            val action = MultiGraphFragmentDirections.actionMultiGraphFragmentToSingleGraphFragment(device.macAddressString)
            findNavController().navigate(action)
        } catch (e: Exception) {
            Log.e("MultiGraphFragment", "Navigation failed: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}