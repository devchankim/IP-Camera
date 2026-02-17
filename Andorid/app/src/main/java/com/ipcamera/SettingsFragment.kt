package com.ipcamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import com.ipcamera.databinding.SettingsFragmentBinding

class SettingsFragment : Fragment() {

    private lateinit var binding: SettingsFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        EdgeToEdge.setInsetsHandler(
            root = binding.root,
            handler = DefaultInsetsHandler(),
        )

        val prefs = SettingsPreferences(requireContext().applicationContext)

        prefs.getIpAddress()?.let { ipAddress ->
            binding.editTextIp.setText(ipAddress)
        }

        prefs.getSignalingToken()?.let { token ->
            binding.editTextToken.setText(token)
        }

        // Defaults: rear camera + standard quality + STUN off
        when (prefs.getCameraFacing()) {
            "front" -> binding.toggleCameraFacing.check(R.id.btn_camera_front)
            else -> binding.toggleCameraFacing.check(R.id.btn_camera_back)
        }

        when (prefs.getQualityPreset()) {
            "low" -> binding.toggleQuality.check(R.id.btn_quality_low)
            "high" -> binding.toggleQuality.check(R.id.btn_quality_high)
            else -> binding.toggleQuality.check(R.id.btn_quality_medium)
        }

        binding.switchStun.isChecked = prefs.isStunFallbackEnabled()

        binding.editTextIp.addTextChangedListener {
            if (binding.textInputIp.error != null) {
                binding.textInputIp.error = null
            }
        }

        binding.btnSave.setOnClickListener {
            val input = binding.editTextIp.text?.toString() ?: ""

            val portSeparatorCount = input.count { it == ':' }

            if (portSeparatorCount != 1 || input.length <= 10) {
                binding.textInputIp.error = "Invalid IP format provided"
                return@setOnClickListener
            }

            val token = binding.editTextToken.text?.toString() ?: ""

            prefs.saveIpAddress(input)
            prefs.saveSignalingToken(token)
            prefs.setCameraFacing(
                if (binding.toggleCameraFacing.checkedButtonId == R.id.btn_camera_front) "front" else "back"
            )
            prefs.setQualityPreset(
                when (binding.toggleQuality.checkedButtonId) {
                    R.id.btn_quality_low -> "low"
                    R.id.btn_quality_high -> "high"
                    else -> "medium"
                }
            )
            prefs.setStunFallbackEnabled(binding.switchStun.isChecked)

            activity?.onBackPressed()
        }
    }
}