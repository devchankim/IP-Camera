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

        prefs.getSignalingToken()?.let { token ->
            binding.editTextToken.setText(token)
        }

        // Defaults: rear camera + standard quality + STUN off
        when (prefs.getCameraFacing()) {
            "front" -> binding.toggleCameraFacing.check(R.id.btn_camera_front)
            else -> binding.toggleCameraFacing.check(R.id.btn_camera_back)
        }

        binding.btnSave.setOnClickListener {
            val token = binding.editTextToken.text?.toString() ?: ""

            prefs.saveSignalingToken(token)
            prefs.setCameraFacing(
                if (binding.toggleCameraFacing.checkedButtonId == R.id.btn_camera_front) "front" else "back"
            )

            activity?.onBackPressed()
        }
    }
}