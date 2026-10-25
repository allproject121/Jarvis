package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.PlanetaryApiRegistry
import com.jarvis.assistant.data.model.PlanetaryApiRegistry.ApiCategory
import com.jarvis.assistant.data.model.PlanetaryApiRegistry.ApiEntry
import com.jarvis.assistant.ui.godseye.GodsEyeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ApiRegistryBottomSheet : BottomSheetDialogFragment() {

    private lateinit var rvApis: RecyclerView
    private lateinit var etSearchApis: EditText
    private lateinit var chipGroupCategories: ChipGroup
    private lateinit var btnPingAllApis: MaterialButton
    private lateinit var tvTotalApiCount: TextView
    private lateinit var adapter: ApiRegistryAdapter

    private var activeCategory: ApiCategory = ApiCategory.ALL
    private var searchQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.sheet_api_registry, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Make dialog expand properly
        dialog?.setOnShowListener { dialogInterface ->
            val d = dialogInterface as? BottomSheetDialog
            val bottomSheet = d?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        rvApis = view.findViewById(R.id.rvApis)
        etSearchApis = view.findViewById(R.id.etSearchApis)
        chipGroupCategories = view.findViewById(R.id.chipGroupCategories)
        btnPingAllApis = view.findViewById(R.id.btnPingAllApis)
        tvTotalApiCount = view.findViewById(R.id.tvTotalApiCount)

        tvTotalApiCount.text = "${PlanetaryApiRegistry.APIS.size} FEEDS"

        adapter = ApiRegistryAdapter(
            apis = PlanetaryApiRegistry.APIS.toMutableList(),
            onPingClick = { api, position ->
                lifecycleScope.launch {
                    PlanetaryApiRegistry.testApiPing(api)
                    adapter.notifyItemChanged(position)
                }
            },
            onViewRawDataClick = { api, tvRawDataBox ->
                lifecycleScope.launch {
                    tvRawDataBox.visibility = View.VISIBLE
                    tvRawDataBox.text = "⚡ Fetching live telemetry payload from ${api.provider}..."
                    val snippet = withContext(Dispatchers.IO) {
                        PlanetaryApiRegistry.fetchRawDataSnippet(api)
                    }
                    tvRawDataBox.text = snippet
                }
            },
            onConnectGlobeClick = { api ->
                val intent = Intent(requireContext(), GodsEyeActivity::class.java).apply {
                    val action = JSONObject().apply {
                        val cam = JSONObject().apply {
                            put("latitude", api.targetLat)
                            put("longitude", api.targetLon)
                            put("altitude", api.targetAlt)
                        }
                        put("camera", cam)
                        if (!api.layerId.isNullOrEmpty()) {
                            put("enable_layers", JSONArray().put(api.layerId))
                        }
                    }
                    putExtra(GodsEyeActivity.EXTRA_ACTION_JSON, action.toString())
                }
                startActivity(intent)
                dismiss()
            }
        )

        rvApis.layoutManager = LinearLayoutManager(requireContext())
        rvApis.adapter = adapter

        setupSearchAndFilters()
        setupPingAll()
    }

    private fun setupSearchAndFilters() {
        etSearchApis.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        chipGroupCategories.setOnCheckedChangeListener { _, checkedId ->
            activeCategory = when (checkedId) {
                R.id.chipAviation -> ApiCategory.AVIATION
                R.id.chipCctv -> ApiCategory.CCTV
                R.id.chipSpace -> ApiCategory.SPACE
                R.id.chipWeather -> ApiCategory.WEATHER
                R.id.chipSeismic -> ApiCategory.SEISMIC
                R.id.chipDefense -> ApiCategory.DEFENSE
                R.id.chipAudio -> ApiCategory.AUDIO
                else -> ApiCategory.ALL
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = PlanetaryApiRegistry.APIS.filter { api ->
            val matchesCategory = activeCategory == ApiCategory.ALL || api.category == activeCategory
            val matchesQuery = searchQuery.isEmpty() ||
                    api.name.contains(searchQuery, ignoreCase = true) ||
                    api.shortSummary.contains(searchQuery, ignoreCase = true) ||
                    api.provider.contains(searchQuery, ignoreCase = true) ||
                    api.category.name.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
        adapter.updateList(filtered)
    }

    private fun setupPingAll() {
        btnPingAllApis.setOnClickListener {
            btnPingAllApis.isEnabled = false
            btnPingAllApis.text = "PINGING..."
            lifecycleScope.launch {
                val currentList = adapter.getCurrentList()
                for ((index, api) in currentList.withIndex()) {
                    api.lastStatus = "TESTING..."
                    adapter.notifyItemChanged(index)
                    PlanetaryApiRegistry.testApiPing(api)
                    adapter.notifyItemChanged(index)
                }
                btnPingAllApis.text = "⚡ PING ALL"
                btnPingAllApis.isEnabled = true
            }
        }
    }

    // --- RECYCLER VIEW ADAPTER ---
    class ApiRegistryAdapter(
        private var apis: List<ApiEntry>,
        private val onPingClick: (ApiEntry, Int) -> Unit,
        private val onViewRawDataClick: (ApiEntry, TextView) -> Unit,
        private val onConnectGlobeClick: (ApiEntry) -> Unit
    ) : RecyclerView.Adapter<ApiRegistryAdapter.ViewHolder>() {

        fun getCurrentList(): List<ApiEntry> = apis

        fun updateList(newList: List<ApiEntry>) {
            apis = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_api_registry, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = apis[position]

            holder.tvApiName.text = item.name
            holder.tvCategoryBadge.text = item.category.name
            holder.tvCategoryBadge.setTextColor(Color.parseColor(item.category.badgeColor))

            holder.tvAuthBadge.text = item.authRequirement.label
            holder.tvApiShortSummary.text = item.shortSummary

            holder.tvDetailHindi.text = item.detailedDescriptionHindi
            holder.tvDetailEnglish.text = item.detailedDescriptionEnglish
            holder.tvGodsEyeRole.text = item.godsEyeRole
            holder.tvProvider.text = "PROVIDER: ${item.provider}"
            holder.tvEndpointUrl.text = item.endpointUrl
            holder.tvVoiceCommand.text = item.voiceCommand

            // Status indicator dot
            val dotColor = when {
                item.lastStatus == "OK" -> Color.parseColor("#00E676")
                item.lastStatus.startsWith("HTTP") -> Color.parseColor("#FFD600")
                item.lastStatus.contains("ERR") || item.lastStatus.contains("TIMEOUT") -> Color.parseColor("#FF3D00")
                item.lastStatus == "TESTING..." -> Color.parseColor("#00E5FF")
                else -> Color.parseColor("#64FFDA")
            }
            holder.dotStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(dotColor)

            holder.tvPingStatus.text = if (item.lastLatencyMs >= 0) "${item.lastStatus} (${item.lastLatencyMs}ms)" else item.lastStatus
            holder.tvPingStatus.setTextColor(dotColor)

            // Detail toggle state
            holder.layoutDetailContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            holder.btnToggleDetail.text = if (item.isExpanded) "CLOSE ▴" else "DETAIL ▾"

            val toggleAction = View.OnClickListener {
                item.isExpanded = !item.isExpanded
                notifyItemChanged(position)
            }
            holder.layoutHeader.setOnClickListener(toggleAction)
            holder.btnToggleDetail.setOnClickListener(toggleAction)

            // Ping test button
            holder.btnTestPing.setOnClickListener {
                holder.tvPingResultDetail.text = "Testing live HTTP ping..."
                holder.tvPingStatus.text = "PINGING..."
                holder.tvPingStatus.setTextColor(Color.parseColor("#00E5FF"))
                onPingClick(item, position)
            }

            if (item.lastLatencyMs >= 0) {
                holder.tvPingResultDetail.text = "Response: ${item.lastStatus} • ${item.lastLatencyMs}ms roundtrip"
            } else {
                holder.tvPingResultDetail.text = "Tap to verify live server connection"
            }

            // View Raw Data Payload button
            holder.btnViewRawData.setOnClickListener {
                if (holder.tvRawDataBox.visibility == View.VISIBLE) {
                    holder.tvRawDataBox.visibility = View.GONE
                } else {
                    onViewRawDataClick(item, holder.tvRawDataBox)
                }
            }

            // Connect to Globe button
            holder.btnConnectGlobe.setOnClickListener {
                onConnectGlobeClick(item)
            }
        }

        override fun getItemCount(): Int = apis.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val layoutHeader: View = view.findViewById(R.id.layoutHeader)
            val dotStatus: View = view.findViewById(R.id.dotStatus)
            val tvCategoryBadge: TextView = view.findViewById(R.id.tvCategoryBadge)
            val tvAuthBadge: TextView = view.findViewById(R.id.tvAuthBadge)
            val tvApiName: TextView = view.findViewById(R.id.tvApiName)
            val tvApiShortSummary: TextView = view.findViewById(R.id.tvApiShortSummary)
            val tvPingStatus: TextView = view.findViewById(R.id.tvPingStatus)
            val btnToggleDetail: TextView = view.findViewById(R.id.btnToggleDetail)

            val layoutDetailContainer: View = view.findViewById(R.id.layoutDetailContainer)
            val tvDetailHindi: TextView = view.findViewById(R.id.tvDetailHindi)
            val tvDetailEnglish: TextView = view.findViewById(R.id.tvDetailEnglish)
            val tvGodsEyeRole: TextView = view.findViewById(R.id.tvGodsEyeRole)
            val tvProvider: TextView = view.findViewById(R.id.tvProvider)
            val tvEndpointUrl: TextView = view.findViewById(R.id.tvEndpointUrl)
            val tvVoiceCommand: TextView = view.findViewById(R.id.tvVoiceCommand)
            val btnTestPing: MaterialButton = view.findViewById(R.id.btnTestPing)
            val btnViewRawData: MaterialButton = view.findViewById(R.id.btnViewRawData)
            val btnConnectGlobe: MaterialButton = view.findViewById(R.id.btnConnectGlobe)
            val tvPingResultDetail: TextView = view.findViewById(R.id.tvPingResultDetail)
            val tvRawDataBox: TextView = view.findViewById(R.id.tvRawDataBox)
        }
    }
}
