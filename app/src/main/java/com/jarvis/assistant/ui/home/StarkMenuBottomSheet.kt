package com.jarvis.assistant.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.databinding.BottomSheetStarkMenuBinding
import com.jarvis.assistant.ui.chat.ChatHistoryBottomSheet
import com.jarvis.assistant.ui.godseye.GodsEyeActivity
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.settings.CustomizationStudioActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.HudSoundManager

class StarkMenuBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetStarkMenuBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetStarkMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        com.jarvis.assistant.util.FrostedGlassEffect.applyWindowBlur(dialog?.window, blurRadius = 45)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Settings & Configuration
        binding.menuItemSettings.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_SETTINGS)
        }

        // 2. Stark HUD Customization Studio
        binding.menuItemStudio.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            startActivity(Intent(requireContext(), CustomizationStudioActivity::class.java))
        }

        // 3. Gemini Chat History
        binding.menuItemChat.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            ChatHistoryBottomSheet.newInstance().show(parentFragmentManager, ChatHistoryBottomSheet.TAG)
        }

        // 4. God's Eye Tactical View
        binding.menuItemGodsEye.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            startActivity(Intent(requireContext(), GodsEyeActivity::class.java))
        }

        // 5. Saved Long-Term Memories
        binding.menuItemMemories.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        // 6. Voice Profile Enrollment
        binding.menuItemVoice.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // 7. JARVIS Agent Matrix & Protocols
        binding.menuItemMatrixHub.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.matrix.JarvisMatrixActivity::class.java))
        }

        // 8. Stark Arc Reactor Live Wallpaper
        binding.menuItemWallpaper.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            try {
                val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        android.content.ComponentName(requireContext(), com.jarvis.assistant.wallpaper.JarvisWallpaperService::class.java)
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                } catch (e2: Exception) {
                    android.widget.Toast.makeText(requireContext(), "JARVIS Live Wallpaper is ready in Wallpaper settings", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 9. Protocol 7 Biometric AppLock
        binding.menuItemAppLock.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            val intent = Intent(requireContext(), com.jarvis.assistant.ui.facelock.JarvisAppLockActivity::class.java).apply {
                putExtra(com.jarvis.assistant.ui.facelock.JarvisAppLockActivity.EXTRA_APP_LABEL, "JARVIS Core Security")
                putExtra(com.jarvis.assistant.ui.facelock.JarvisAppLockActivity.EXTRA_PACKAGE_NAME, requireContext().packageName)
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "StarkMenuBottomSheet"
        fun newInstance() = StarkMenuBottomSheet()
    }
}
