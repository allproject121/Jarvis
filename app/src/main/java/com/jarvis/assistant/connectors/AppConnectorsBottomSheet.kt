package com.jarvis.assistant.connectors

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.BottomSheetAppConnectorsBinding
import com.jarvis.assistant.util.HudSoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Rich interactive BottomSheet for viewing, toggling, searching, and managing
 * Universal App Connectors across ALL installed applications on the device.
 */
class AppConnectorsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAppConnectorsBinding? = null
    private val binding get() = _binding!!
    private val manager by lazy { AppConnectorsManager.getInstance(requireContext()) }
    private val pm by lazy { requireContext().packageManager }

    private var currentFilterMode = FilterMode.ALL
    private var currentSearchQuery = ""

    private enum class FilterMode { ALL, CONNECTED, DISCONNECTED }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAppConnectorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        com.jarvis.assistant.util.FrostedGlassEffect.applyWindowBlur(dialog?.window, blurRadius = 45)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseConnectors.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
        }

        binding.btnAddExternalConnector.setOnClickListener {
            HudSoundManager.playTapSound()
            showAddExternalConnectorDialog()
        }

        binding.btnConnectAllApps.setOnClickListener {
            HudSoundManager.playTapSound()
            showConnectAllWarningDialog()
        }

        setupSearchAndFilterChips()
        populateConnectorsList()
    }

    private fun setupSearchAndFilterChips() {
        // Search text watcher
        binding.etSearchConnectors.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                populateConnectorsList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Filter chips
        binding.chipFilterAll.setOnClickListener {
            HudSoundManager.playTapSound()
            setFilterMode(FilterMode.ALL)
        }

        binding.chipFilterConnected.setOnClickListener {
            HudSoundManager.playTapSound()
            setFilterMode(FilterMode.CONNECTED)
        }

        binding.chipFilterDisconnected.setOnClickListener {
            HudSoundManager.playTapSound()
            setFilterMode(FilterMode.DISCONNECTED)
        }
    }

    private fun setFilterMode(mode: FilterMode) {
        currentFilterMode = mode

        binding.chipFilterAll.setBackgroundResource(if (mode == FilterMode.ALL) R.drawable.bg_jarvis_pill_active else R.drawable.bg_jarvis_pill_inactive)
        binding.chipFilterAll.setTextColor(if (mode == FilterMode.ALL) Color.WHITE else Color.parseColor("#94A3B8"))

        binding.chipFilterConnected.setBackgroundResource(if (mode == FilterMode.CONNECTED) R.drawable.bg_jarvis_pill_active else R.drawable.bg_jarvis_pill_inactive)
        binding.chipFilterConnected.setTextColor(if (mode == FilterMode.CONNECTED) Color.WHITE else Color.parseColor("#94A3B8"))

        binding.chipFilterDisconnected.setBackgroundResource(if (mode == FilterMode.DISCONNECTED) R.drawable.bg_jarvis_pill_active else R.drawable.bg_jarvis_pill_inactive)
        binding.chipFilterDisconnected.setTextColor(if (mode == FilterMode.DISCONNECTED) Color.WHITE else Color.parseColor("#94A3B8"))

        populateConnectorsList()
    }

    private fun populateConnectorsList() {
        val container = binding.layoutConnectorsList
        container.removeAllViews()

        val allConnectors = manager.getConnectors()
        val totalCount = allConnectors.size
        val connectedCount = allConnectors.count { it.isConnected }
        val disconnectedCount = totalCount - connectedCount

        binding.tvConnectorsSubtitle.text = "$connectedCount of $totalCount apps connected with Full Autonomous Access"
        binding.chipFilterAll.text = "ALL APPS ($totalCount)"
        binding.chipFilterConnected.text = "CONNECTED ($connectedCount)"
        binding.chipFilterDisconnected.text = "BASIC ($disconnectedCount)"

        val filtered = allConnectors.filter { conn ->
            // Filter by Mode
            val modeMatches = when (currentFilterMode) {
                FilterMode.ALL -> true
                FilterMode.CONNECTED -> conn.isConnected
                FilterMode.DISCONNECTED -> !conn.isConnected
            }
            // Filter by Search Query
            val queryMatches = if (currentSearchQuery.isBlank()) {
                true
            } else {
                conn.name.lowercase(Locale.ROOT).contains(currentSearchQuery) ||
                conn.packageName.lowercase(Locale.ROOT).contains(currentSearchQuery) ||
                conn.category.lowercase(Locale.ROOT).contains(currentSearchQuery)
            }
            modeMatches && queryMatches
        }

        val density = resources.displayMetrics.density

        if (filtered.isEmpty()) {
            val emptyTv = TextView(requireContext()).apply {
                text = if (currentSearchQuery.isNotBlank()) "No applications found matching '$currentSearchQuery'" else "No applications in this category"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (32 * density).toInt(), 0, (32 * density).toInt())
            }
            container.addView(emptyTv)
            return
        }

        for (conn in filtered) {
            val card = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_jarvis_card)
                setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            }

            // Top Header: App Icon, Info (Name + Category + Status), and Switch
            val headerRel = RelativeLayout(requireContext()).apply {
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // App Icon Frame
            val iconFrame = FrameLayout(requireContext()).apply {
                id = View.generateViewId()
                val sizePx = (38 * density).toInt()
                layoutParams = RelativeLayout.LayoutParams(sizePx, sizePx).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
                setBackgroundResource(R.drawable.bg_jarvis_icon_square)
            }

            val ivIcon = ImageView(requireContext()).apply {
                val iconSizePx = (24 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
                val appIcon = loadAppIcon(conn.packageName)
                if (appIcon != null) {
                    setImageDrawable(appIcon)
                } else {
                    setImageResource(R.drawable.ic_verified_badge)
                    setColorFilter(Color.parseColor("#00E5FF"))
                }
                contentDescription = conn.name
            }
            iconFrame.addView(ivIcon)
            headerRel.addView(iconFrame)

            // Switch (Right aligned)
            val toggleSwitch = SwitchMaterial(requireContext()).apply {
                id = View.generateViewId()
                isChecked = conn.isConnected
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
            }
            headerRel.addView(toggleSwitch)

            // Title and Badges Column (Between icon and switch)
            val titleCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.END_OF, iconFrame.id)
                    addRule(RelativeLayout.START_OF, toggleSwitch.id)
                    marginStart = (10 * density).toInt()
                    marginEnd = (8 * density).toInt()
                }
            }

            // App Name & Category
            val nameRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(requireContext()).apply {
                text = conn.name
                setTextColor(Color.WHITE)
                textSize = 14f
                paint.isFakeBoldText = true
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            nameRow.addView(tvTitle)

            val tvCategory = TextView(requireContext()).apply {
                text = conn.category
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 9.5f
                paint.isFakeBoldText = true
                setPadding((6 * density).toInt(), (1 * density).toInt(), (6 * density).toInt(), (1 * density).toInt())
                setBackgroundResource(R.drawable.bg_jarvis_pill_inactive)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (6 * density).toInt()
                }
                layoutParams = lp
            }
            nameRow.addView(tvCategory)
            titleCol.addView(nameRow)

            // Status Badge
            val tvStatus = TextView(requireContext()).apply {
                text = if (conn.isConnected) "🟢 FULL AUTONOMOUS ACCESS (CONNECTED)" else "⚪ BASIC AUTOMATION ONLY (NOT CONNECTED)"
                setTextColor(if (conn.isConnected) Color.parseColor("#4ADE80") else Color.parseColor("#94A3B8"))
                textSize = 10f
                paint.isFakeBoldText = true
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            titleCol.addView(tvStatus)

            headerRel.addView(titleCol)
            card.addView(headerRel)

            // Description / Capability Summary
            val tvDesc = TextView(requireContext()).apply {
                text = conn.description
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            card.addView(tvDesc)

            // Switch Logic: Shows Privacy Warning before turning ON
            toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                HudSoundManager.playTapSound()
                if (isChecked) {
                    showPrivacyRiskWarningDialog(conn) {
                        manager.setConnectorEnabled(conn.id, true, lifecycleScope)
                        tvStatus.text = "🟢 FULL AUTONOMOUS ACCESS (CONNECTED)"
                        tvStatus.setTextColor(Color.parseColor("#4ADE80"))
                        Toast.makeText(requireContext(), "Connected '${conn.name}' with Full Access!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    manager.setConnectorEnabled(conn.id, false, lifecycleScope)
                    tvStatus.text = "⚪ BASIC AUTOMATION ONLY (NOT CONNECTED)"
                    tvStatus.setTextColor(Color.parseColor("#94A3B8"))
                    Toast.makeText(requireContext(), "Disconnected '${conn.name}'", Toast.LENGTH_SHORT).show()
                }
            }

            container.addView(card)
        }
    }

    private fun loadAppIcon(packageName: String): Drawable? {
        if (packageName == "phone") {
            return ContextCompat.getDrawable(requireContext(), R.drawable.ic_settings_connectors)
        }
        if (packageName == "contacts") {
            return ContextCompat.getDrawable(requireContext(), R.drawable.ic_user_profile)
        }
        return try {
            pm.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    private fun showConnectAllWarningDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚡ CONNECT ALL APPS — HIGH RISK CONSENT")
            .setMessage(
                "Connecting ALL installed applications grants Jarvis FULL AUTONOMOUS ACCESS to your entire phone:\n\n" +
                "• Autonomous access to Google Drive, Files, WhatsApp, Gmail, Photos, Chrome, Settings, etc.\n" +
                "• Permission to search files, open documents, upload/save content, and delete items without prompt\n" +
                "• Autonomous indexing of app data into Neural Vector Semantic Memory\n\n" +
                "Are you sure you want to authorize FULL ACCESS for all apps at your own risk?"
            )
            .setPositiveButton("AUTHORIZE ALL APPS") { _, _ ->
                manager.connectAllApps(lifecycleScope)
                populateConnectorsList()
                Toast.makeText(requireContext(), "Authorized ALL installed apps with Full Access!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .setCancelable(false)
            .show()
    }

    private fun showPrivacyRiskWarningDialog(connector: AppConnector, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ HIGH-RISK ACCESS & PRIVACY WARNING")
            .setMessage(
                "Connecting '${connector.name}' grants Jarvis autonomous access to:\n\n" +
                "• Full access to inspect internal data, search files, read contents, and delete items\n" +
                "• Autonomously execute in-app tasks without repetitive confirmation prompts\n" +
                "• Ingest interactions and app capabilities into On-Device Neural Vector Memory\n\n" +
                "Your privacy could be affected. Do you accept the full access grant at your own risk?"
            )
            .setPositiveButton("I UNDERSTAND & ACCEPT RISK") { _, _ ->
                onConfirm()
            }
            .setNegativeButton("CANCEL") { dialog, _ ->
                dialog.dismiss()
                populateConnectorsList() // reset switch visually
            }
            .setCancelable(false)
            .show()
    }

    private fun showAddExternalConnectorDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        val etName = EditText(requireContext()).apply {
            hint = "App / Service Name (e.g. Termux Server, Kodi)"
            textSize = 13.5f
        }
        val etPackage = EditText(requireContext()).apply {
            hint = "Package Name or URI Scheme (e.g. com.example.app)"
            textSize = 13.5f
        }
        val etEndpoint = EditText(requireContext()).apply {
            hint = "Local Bridge Endpoint (optional, e.g. http://127.0.0.1:8080)"
            textSize = 13.5f
        }

        layout.addView(etName)
        layout.addView(etPackage)
        layout.addView(etEndpoint)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add External App / Plugin")
            .setMessage("Register an uninstalled app, custom scheme, or local bridge for autonomous control:")
            .setView(layout)
            .setPositiveButton("ADD CONNECTOR") { _, _ ->
                val name = etName.text.toString().trim()
                val pkg = etPackage.text.toString().trim()
                val endpoint = etEndpoint.text.toString().trim()
                if (name.isNotBlank() && pkg.isNotBlank()) {
                    manager.addExternalConnector(
                        name = name,
                        packageNameOrEndpoint = pkg,
                        description = "Custom external plugin connector for autonomous bridge communication.",
                        bridgeEndpoint = endpoint
                    )
                    populateConnectorsList()
                    Toast.makeText(requireContext(), "Added external connector '$name'!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Name and Package are required.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AppConnectorsBottomSheet"
        fun newInstance() = AppConnectorsBottomSheet()
    }
}
