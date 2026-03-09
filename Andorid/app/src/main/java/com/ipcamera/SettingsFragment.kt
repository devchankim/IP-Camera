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

        // ── Connection ────────────────────────────────────────────────────────
        prefs.getIpAddress()?.let { binding.editTextIp.setText(it) }
        prefs.getSignalingToken()?.let { binding.editTextToken.setText(it) }
        binding.editTextRoom.setText(prefs.getRoomName())

        // ── Camera ────────────────────────────────────────────────────────────
        when (prefs.getCameraFacing()) {
            "front" -> binding.toggleCameraFacing.check(R.id.btn_camera_front)
            else    -> binding.toggleCameraFacing.check(R.id.btn_camera_back)
        }
        when (prefs.getQualityPreset()) {
            "low"  -> binding.toggleQuality.check(R.id.btn_quality_low)
            "high" -> binding.toggleQuality.check(R.id.btn_quality_high)
            else   -> binding.toggleQuality.check(R.id.btn_quality_medium)
        }

        // ── Switches ──────────────────────────────────────────────────────────
        binding.switchStun.isChecked       = prefs.isStunFallbackEnabled()
        binding.switchNightMode.isChecked  = prefs.isNightModeEnabled()
        binding.switchCryDetect.isChecked  = prefs.isCryDetectEnabled()

        // ── ntfy ──────────────────────────────────────────────────────────────
        binding.editTextNtfyTopic.setText(prefs.getNtfyTopic())
        val savedBaseUrl = prefs.getNtfyBaseUrl()
        if (savedBaseUrl != "https://ntfy.sh") {
            binding.editTextNtfyBaseUrl.setText(savedBaseUrl)
        }

        // ── TURN ──────────────────────────────────────────────────────────────
        binding.editTextTurnUrl.setText(prefs.getTurnUrl())
        binding.editTextTurnUser.setText(prefs.getTurnUsername())
        binding.editTextTurnCred.setText(prefs.getTurnCredential())

        // Clear validation errors on typing
        binding.editTextIp.addTextChangedListener {
            if (binding.textInputIp.error != null) binding.textInputIp.error = null
        }

        // ── Save ──────────────────────────────────────────────────────────────
        binding.btnSave.setOnClickListener {
            val ipInput = binding.editTextIp.text?.toString() ?: ""
            val colonCount = ipInput.count { it == ':' }
            if (colonCount != 1 || ipInput.length <= 10) {
                binding.textInputIp.error = "Invalid IP format provided"
                return@setOnClickListener
            }

            prefs.saveIpAddress(ipInput)
            prefs.saveSignalingToken(binding.editTextToken.text?.toString() ?: "")
            prefs.setRoomName(binding.editTextRoom.text?.toString() ?: "baby")

            prefs.setCameraFacing(
                if (binding.toggleCameraFacing.checkedButtonId == R.id.btn_camera_front) "front" else "back"
            )
            prefs.setQualityPreset(
                when (binding.toggleQuality.checkedButtonId) {
                    R.id.btn_quality_low  -> "low"
                    R.id.btn_quality_high -> "high"
                    else                  -> "medium"
                }
            )

            prefs.setStunFallbackEnabled(binding.switchStun.isChecked)
            prefs.setNightModeEnabled(binding.switchNightMode.isChecked)
            prefs.setCryDetectEnabled(binding.switchCryDetect.isChecked)

            // ntfy
            prefs.setNtfyTopic(binding.editTextNtfyTopic.text?.toString() ?: "")
            val ntfyBase = binding.editTextNtfyBaseUrl.text?.toString()?.trim() ?: ""
            prefs.setNtfyBaseUrl(ntfyBase)

            // TURN
            prefs.setTurnUrl(binding.editTextTurnUrl.text?.toString() ?: "")
            prefs.setTurnUsername(binding.editTextTurnUser.text?.toString() ?: "")
            prefs.setTurnCredential(binding.editTextTurnCred.text?.toString() ?: "")

            @Suppress("DEPRECATION")
            activity?.onBackPressed()
        }
    }
}
