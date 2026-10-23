package com.jarvis.assistant.ui.home

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.DialogStarkTopMenuBinding
import com.jarvis.assistant.ui.chat.ChatHistoryBottomSheet
import com.jarvis.assistant.ui.godseye.GodsEyeActivity
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.settings.CustomizationStudioActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.HudSoundManager

class StarkTopMenuDialog : DialogFragment() {

    private var _binding: DialogStarkTopMenuBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogStarkTopMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val menuWidth = minOf((400 * density).toInt(), (screenWidth * 0.95f).toInt())
            setLayout(menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.START)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // Apply centralized blur behind according to user preference
            com.jarvis.assistant.util.FrostedGlassEffect.applyWindowBlur(this, blurRadius = 50, dimAmount = 0.25f)

            val lp = attributes
            lp.x = (10 * density).toInt()
            lp.y = (58 * density).toInt()
            attributes = lp
            setWindowAnimations(R.style.StarkTopMenuAnimation)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listOf(
            binding.btnCloseTopMenu,
            binding.menuItemSettings,
            binding.menuItemStudio,
            binding.menuItemChat,
            binding.menuItemGodsEye,
            binding.menuItemMemories,
            binding.menuItemVoice,
            binding.menuItemMatrixHub,
            binding.menuItemSuperCore
        ).forEach { com.jarvis.assistant.util.HudAnimationHelper.attachSpringTouch(it) }

        binding.btnCloseTopMenu.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
        }

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

        // 8. SuperCore & System Control
        binding.menuItemSuperCore.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.supercore.SuperCoreActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "StarkTopMenuDialog"
        fun newInstance() = StarkTopMenuDialog()
    }
}
