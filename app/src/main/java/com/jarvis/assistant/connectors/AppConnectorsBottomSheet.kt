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

        binding.btnServiceConnectors.setOnClickListener {
            HudSoundManager.playTapSound()
            startActivity(com.jarvis.assistant.maya.connectors.ConnectorsActivity.intent(requireContext()))
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

        binding.chipFilterAll.setBackgroundResource(if (mode == FilterMode.ALL) R.drawable.bg_bento_x_chip_on else R.drawable.bg_set_chip)
        binding.chipFilterAll.setTextColor(if (mode == FilterMode.ALL) com.jarvis.assistant.util.ThemeManager.onAccentColor(requireContext()) else themeColor(R.attr.themeTextSecondary, Color.parseColor("#94A3B8")))

        binding.chipFilterConnected.setBackgroundResource(if (mode == FilterMode.CONNECTED) R.drawable.bg_bento_x_chip_on else R.drawable.bg_set_chip)
        binding.chipFilterConnected.setTextColor(if (mode == FilterMode.CONNECTED) com.jarvis.assistant.util.ThemeManager.onAccentColor(requireContext()) else themeColor(R.attr.themeTextSecondary, Color.parseColor("#94A3B8")))

        binding.chipFilterDisconnected.setBackgroundResource(if (mode == FilterMode.DISCONNECTED) R.drawable.bg_bento_x_chip_on else R.drawable.bg_set_chip)
        binding.chipFilterDisconnected.setTextColor(if (mode == FilterMode.DISCONNECTED) com.jarvis.assistant.util.ThemeManager.onAccentColor(requireContext()) else themeColor(R.attr.themeTextSecondary, Color.parseColor("#94A3B8")))

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
        binding.chipFilterAll.text = "All apps ($totalCount)"
        binding.chipFilterConnected.text = "Connected ($connectedCount)"
        binding.chipFilterDisconnected.text = "Basic ($disconnectedCount)"

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
                setTextColor(themeColor(R.attr.themeTextSecondary, Color.parseColor("#94A3B8")))
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
                    bottomMargin = (8 * density).toInt()
                }
                orientation = LinearLayout.VERTICAL
                // Dark Bento inner plate (2026-11-05 bento-all)
                setBackgroundResource(R.drawable.bg_jarvis_row_item)
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
                val sizePx = (40 * density).toInt()
                layoutParams = RelativeLayout.LayoutParams(sizePx, sizePx).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
                setBackgroundResource(R.drawable.bg_set_icon_tile)
            }

            val ivIcon = ImageView(requireContext()).apply {
                val iconSizePx = (24 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
                val appIcon = loadAppIcon(conn.packageName)
                if (appIcon != null) {
                    setImageDrawable(appIcon)
                } else {
                    setImageResource(R.drawable.ic_verified_badge)
                    setColorFilter(themeColor(R.attr.themeAccent, Color.parseColor("#00E5FF")))
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
                setTextColor(themeColor(R.attr.themeTextPrimary, Color.WHITE))
                textSize = 15f
                bentoFont(this, bold = true)
                maxLines = 1
                // shrink before the category pill so the pill never gets pushed off the plate
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            nameRow.addView(tvTitle)

            val tvCategory = TextView(requireContext()).apply {
                text = conn.category
                setTextColor(themeColor(R.attr.themeTextSecondary, Color.parseColor("#94A3B8")))
                textSize = 11f
                bentoFont(this, bold = false)
                maxLines = 1
                setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
                setBackgroundResource(R.drawable.bg_set_badge)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (6 * density).toInt()
                }
                layoutParams = lp
            }
            nameRow.addView(tvCategory)
            titleCol.addView(nameRow)

            // Status Badge
            val currentStatus = manager.computeConnectorStatus(conn)
            val tvStatus = TextView(requireContext()).apply {
                text = "● " + currentStatus.label
                setTextColor(Color.parseColor(currentStatus.colorHex))
                textSize = 12f
                bentoFont(this, bold = false)
                setPadding(0, (3 * density).toInt(), 0, 0)
            }
            titleCol.addView(tvStatus)

            headerRel.addView(titleCol)
            card.addView(headerRel)

            // Description / Capability Summary
            val tvDesc = TextView(requireContext()).apply {
                text = conn.description
                setTextColor(themeColor(R.attr.themeTextMuted, Color.parseColor("#94A3B8")))
                textSize = 12f
                bentoFont(this, bold = false)
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
            card.addView(tvDesc)

            // Switch Logic: Shows Privacy Warning before turning ON
            toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                HudSoundManager.playTapSound()
                if (isChecked) {
                    showPrivacyRiskWarningDialog(conn) {
                        manager.setConnectorEnabled(conn.id, true, lifecycleScope)
                        conn.isConnected = true
                        val newStatus = manager.computeConnectorStatus(conn)
                        tvStatus.text = "● " + newStatus.label
                        tvStatus.setTextColor(Color.parseColor(newStatus.colorHex))
                        Toast.makeText(requireContext(), "Connected '" + conn.name + "' (" + newStatus.label + ")", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    manager.setConnectorEnabled(conn.id, false, lifecycleScope)
                    conn.isConnected = false
                    val newStatus = manager.computeConnectorStatus(conn)
                    tvStatus.text = "● " + newStatus.label
                    tvStatus.setTextColor(Color.parseColor(newStatus.colorHex))
                    Toast.makeText(requireContext(), "Disconnected '" + conn.name + "'", Toast.LENGTH_SHORT).show()
                }
            }

            // Token-based cloud services (Notion, Linear, Todoist, GitLab, Drive, Vercel, Telegram, GitHub):
            // linking happens in ConnectorsActivity, so the switch and the card open it instead of toggling.
            if (manager.isServiceConnector(conn)) {
                val serviceId = conn.packageName.removePrefix(AppConnectorsManager.MAYA_SERVICE_PREFIX)
                val openConnectors = View.OnClickListener {
                    HudSoundManager.playTapSound()
                    startActivity(com.jarvis.assistant.maya.connectors.ConnectorsActivity.intent(requireContext(), serviceId))
                }
                toggleSwitch.setOnCheckedChangeListener(null)
                toggleSwitch.isClickable = false
                toggleSwitch.isFocusable = false
                card.isClickable = true
                card.setOnClickListener(openConnectors)
                val tvLink = TextView(requireContext()).apply {
                    text = if (conn.isConnected) "Manage token ›" else "Link token ›"
                    setTextColor(themeColor(R.attr.themeAccent, Color.parseColor("#00E5FF")))
                    textSize = 13f
                    bentoFont(this, bold = true)
                    setPadding(0, (8 * density).toInt(), 0, 0)
                    setOnClickListener(openConnectors)
                }
                card.addView(tvLink)
            }

            container.addView(card)
        }
    }

    /** Dark Bento type (2026-11-05 bento-all): Inter, semibold for titles (falls back to the default face). */
    private fun bentoFont(tv: TextView, bold: Boolean) {
        val inter = try { androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter) } catch (_: Exception) { null }
        if (inter != null) {
            tv.typeface = if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.graphics.Typeface.create(inter, if (bold) 600 else 400, false)
            } else {
                android.graphics.Typeface.create(inter, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        } else if (bold) {
            tv.paint.isFakeBoldText = true
        }
    }

    /** Resolve a Jarvis theme colour attr so code-built rows follow the active theme. */
    private fun themeColor(attr: Int, fallback: Int): Int =
        com.jarvis.assistant.util.ThemeManager.attrColor(requireContext(), attr, fallback)

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
                "Connecting ALL installed applications lets Jarvis act on every app:\n\n" +
                "• Open any installed app and tap/type on its screen via Accessibility\n" +
                "• What it can do inside each app depends on that app's layout — there is no app-specific API access\n" +
                "• Each app's connection is recorded in Jarvis' on-device memory\n\n" +
                "Are you sure you want to connect all apps at your own risk?"
            )
            .setPositiveButton("AUTHORIZE ALL APPS") { _, _ ->
                manager.connectAllApps(lifecycleScope)
                populateConnectorsList()
                Toast.makeText(requireContext(), "Connected all installed apps", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .setCancelable(false)
            .show()
    }

    private fun showPrivacyRiskWarningDialog(connector: AppConnector, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ HIGH-RISK ACCESS & PRIVACY WARNING")
            .setMessage(
                "Connecting '${connector.name}' lets Jarvis:\n\n" +
                "• Open this app and tap/type on its screen via Accessibility (app-specific actions depend on the app's layout)\n" +
                "• Read what is visible on its screen while it acts\n" +
                "• Record the connection in Jarvis' on-device memory\n\n" +
                "Your privacy could be affected. Do you accept at your own risk?"
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

        layout.addView(etName)
        layout.addView(etPackage)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add External App / Plugin")
            .setMessage("Register an app by package name so Jarvis can open it and tap/type on its screen via Accessibility:")
            .setView(layout)
            .setPositiveButton("ADD CONNECTOR") { _, _ ->
                val name = etName.text.toString().trim()
                val pkg = etPackage.text.toString().trim()
                if (name.isNotBlank() && pkg.isNotBlank()) {
                    manager.addExternalConnector(
                        name = name,
                        packageNameOrEndpoint = pkg,
                        description = AppConnectorsManager.ACCESSIBILITY_CAPABILITY
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

    override fun onResume() {
        super.onResume()
        // Refresh service-connector status after returning from ConnectorsActivity.
        if (_binding != null) {
            manager.refreshConnectorsList()
            populateConnectorsList()
        }
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
