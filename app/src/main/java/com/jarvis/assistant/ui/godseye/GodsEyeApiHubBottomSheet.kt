package com.jarvis.assistant.ui.godseye

import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.SheetGodseyeApiHubBinding
import com.jarvis.assistant.util.FrostedGlassEffect
import kotlinx.coroutines.launch

/**
 * Interactive Cyber HUD Bottom Sheet providing Direct Connect access to
 * all planetary sensor feeds and intelligence APIs discovered in God's Eye.
 */
class GodsEyeApiHubBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetGodseyeApiHubBinding? = null
    private val binding get() = _binding!!

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingUrl: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetGodseyeApiHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = (d as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.75).toInt()
                sheet.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        FrostedGlassEffect.applyWindowBlur(dialog?.window, blurRadius = 50, dimAmount = 0.35f)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnHubClose.setOnClickListener {
            dismiss()
        }

        setupLaunchesSection()
        setupRadioSection()
        setupAircraftSection()
        setupCyclonesSection()
        setupNewsSection()
        setupCctvSection()
        setupAutoTourSection()
    }

    // ==========================================
    // 0. AUTONOMOUS SENTINEL PATROL
    // ==========================================
    private fun setupAutoTourSection() {
        val act = activity as? GodsEyeActivity
        val isRunning = act?.isAutoTourRunning() ?: false
        updateAutoTourButtonState(isRunning)

        binding.btnToggleAutoTourHub.setOnClickListener {
            act?.let { gAct ->
                gAct.toggleAutoTour()
                val nowRunning = gAct.isAutoTourRunning()
                updateAutoTourButtonState(nowRunning)
                if (nowRunning) {
                    dismiss()
                }
            }
        }
    }

    private fun updateAutoTourButtonState(isRunning: Boolean) {
        if (isRunning) {
            binding.btnToggleAutoTourHub.text = "STOP ⏹"
            binding.btnToggleAutoTourHub.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF3D00"))
            binding.btnToggleAutoTourHub.setTextColor(Color.WHITE)
        } else {
            binding.btnToggleAutoTourHub.text = "START ⟳"
            binding.btnToggleAutoTourHub.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
            binding.btnToggleAutoTourHub.setTextColor(Color.parseColor("#050811"))
        }
    }

    // ==========================================
    // 6. WORLDWIDE REAL CCTV SURVEILLANCE
    // ==========================================
    private fun setupCctvSection() {
        val cameras = listOf(
            Triple("354", "🇺🇸 Austin, Texas • 5th St / Congress", "Live City HD Snapshot • Austin Mobility IoT"),
            Triple("ca-d11-x13", "🇺🇸 San Diego, California • I-5 First Ave", "Live Caltrans Highway Network • D11"),
            Triple("tfl-00001.06502", "🇬🇧 London, UK • Trafalgar Square", "Live Video • Transport for London (TfL JamCams)"),
            Triple("hk-h107f", "🇭🇰 Hong Kong • Connaught Road West", "Live Urban Stream • HK Transport Department"),
            Triple("fi-c1455101", "🇫🇮 Finland • Rovaniemi Revontuli (Arctic)", "Live Snow / Highway Cam • Fintraffic"),
            Triple("calgary-222", "🇨🇦 Calgary, Canada • Downtown Transit", "Live City Traffic • Calgary Roads"),
            Triple("no-vegvesen-3000051_1", "🇳🇴 Norway • Sandsbråten Mountain Pass", "Live Nordic Highway • Statens Vegvesen")
        )

        binding.containerCctvPresets.removeAllViews()
        cameras.forEach { (camId, title, desc) ->
            val card = createInfoCard(
                title = title,
                subtitle = "ID: $camId",
                details = "$desc\n[Tap to fly 3D globe & project feed]",
                accentColor = "#10B981"
            ) {
                lifecycleScope.launch {
                    try {
                        val args = org.json.JSONObject().apply {
                            put("camera_id", camId)
                        }
                        GodsEyeMcpClient.handleSelectCamera(args)
                        Toast.makeText(requireContext(), "Centering God's Eye on $title...", Toast.LENGTH_SHORT).show()
                        dismiss()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Camera engage: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            binding.containerCctvPresets.addView(card)
        }
    }

    // ==========================================
    // 1. SPACE ROCKET LAUNCHES
    // ==========================================
    private fun setupLaunchesSection() {
        binding.btnConnectLaunches.setOnClickListener {
            binding.pbLaunches.visibility = View.VISIBLE
            binding.containerLaunches.removeAllViews()

            lifecycleScope.launch {
                val result = GodsEyeApiHub.fetchUpcomingLaunches()
                binding.pbLaunches.visibility = View.GONE

                result.onSuccess { launches ->
                    if (launches.isEmpty()) {
                        addSimpleTextView(binding.containerLaunches, "No upcoming launches scheduled in current window.")
                    } else {
                        launches.take(6).forEach { launch ->
                            val card = createInfoCard(
                                title = launch.name,
                                subtitle = "${launch.provider} • NET: ${formatIsoDate(launch.netTime)}",
                                details = "Pad: ${launch.padName} (${launch.location})\nStatus: ${launch.status}\n${launch.missionDescription ?: ""}".trim(),
                                accentColor = "#00D4FF"
                            )
                            binding.containerLaunches.addView(card)
                        }
                    }
                }.onFailure { err ->
                    addSimpleTextView(binding.containerLaunches, "Error fetching launches: ${err.message}", "#FF4444")
                }
            }
        }
    }

    // ==========================================
    // 2. WORLDWIDE LIVE RADIO
    // ==========================================
    private fun setupRadioSection() {
        binding.btnConnectRadioGlobal.setOnClickListener {
            fetchAndDisplayRadio(genre = null, country = null)
        }

        binding.btnConnectRadioIndia.setOnClickListener {
            fetchAndDisplayRadio(genre = null, country = "India")
        }

        binding.btnConnectRadioAmbient.setOnClickListener {
            fetchAndDisplayRadio(genre = "ambient", country = null)
        }

        binding.btnStopRadio.setOnClickListener {
            stopAudioStream()
        }
    }

    private fun fetchAndDisplayRadio(genre: String?, country: String?) {
        binding.pbRadio.visibility = View.VISIBLE
        binding.containerRadio.removeAllViews()

        lifecycleScope.launch {
            val result = GodsEyeApiHub.fetchRadioStations(genre, country)
            binding.pbRadio.visibility = View.GONE

            result.onSuccess { stations ->
                if (stations.isEmpty()) {
                    addSimpleTextView(binding.containerRadio, "No live radio streams found for selected filter.")
                } else {
                    stations.take(8).forEach { station ->
                        val card = createRadioStationCard(station)
                        binding.containerRadio.addView(card)
                    }
                }
            }.onFailure { err ->
                addSimpleTextView(binding.containerRadio, "Error loading radio stations: ${err.message}", "#FF4444")
            }
        }
    }

    private fun createRadioStationCard(station: GodsEyeApiHub.RadioStation): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 16, 20, 16)
            setBackgroundResource(R.drawable.bg_godseye_hud_back)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10
            }
            layoutParams = lp
        }

        val infoLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            layoutParams = lp
        }

        val tvName = TextView(requireContext()).apply {
            text = station.name
            setTextColor(Color.WHITE)
            textSize = 12f
            paint.isFakeBoldText = true
        }

        val tvMeta = TextView(requireContext()).apply {
            val countryTag = if (station.country.isNotBlank()) "${station.country} • " else ""
            val codecTag = if (station.codec.isNotBlank()) "${station.codec} ${station.bitrate}kbps" else ""
            text = "$countryTag$codecTag".trim().removeSuffix("•").trim()
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
        }

        infoLayout.addView(tvName)
        infoLayout.addView(tvMeta)

        val btnPlay = Button(requireContext()).apply {
            text = if (currentlyPlayingUrl == station.streamUrl) "STOP" else "PLAY"
            setTextColor(if (currentlyPlayingUrl == station.streamUrl) Color.parseColor("#FF3B30") else Color.BLACK)
            textSize = 10f
            paint.isFakeBoldText = true
            setBackgroundResource(R.drawable.bg_godseye_hud_back)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (currentlyPlayingUrl == station.streamUrl) Color.parseColor("#331215") else Color.parseColor("#10B981")
            )
            setOnClickListener {
                if (currentlyPlayingUrl == station.streamUrl) {
                    stopAudioStream()
                    text = "PLAY"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
                } else {
                    playAudioStream(station.streamUrl, station.name)
                    text = "STOP"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#331215"))
                }
            }
        }

        root.addView(infoLayout)
        root.addView(btnPlay)
        return root
    }

    private fun playAudioStream(streamUrl: String, stationName: String) {
        try {
            stopAudioStream()

            binding.layoutRadioPlayer.visibility = View.VISIBLE
            binding.tvCurrentPlayingStation.text = "Connecting: $stationName..."

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(streamUrl)
                setOnPreparedListener { mp ->
                    mp.start()
                    currentlyPlayingUrl = streamUrl
                    binding.tvCurrentPlayingStation.text = "LIVE: $stationName"
                    Toast.makeText(requireContext(), "Streaming: $stationName", Toast.LENGTH_SHORT).show()
                }
                setOnErrorListener { _, what, extra ->
                    binding.tvCurrentPlayingStation.text = "Stream error ($what)"
                    stopAudioStream()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            binding.tvCurrentPlayingStation.text = "Failed to open stream"
            Toast.makeText(requireContext(), "Stream error: ${e.message}", Toast.LENGTH_SHORT).show()
            stopAudioStream()
        }
    }

    private fun stopAudioStream() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentlyPlayingUrl = null
        binding.layoutRadioPlayer.visibility = View.GONE
    }

    // ==========================================
    // 3. AIRCRAFT INTEL & ROUTE ENRICHMENT
    // ==========================================
    private fun setupAircraftSection() {
        binding.btnLookupAircraft.setOnClickListener {
            val query = binding.etAircraftQuery.text?.toString()?.trim().orEmpty()
            if (query.isBlank()) {
                Toast.makeText(requireContext(), "Please enter a Callsign or Hex", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.pbAircraft.visibility = View.VISIBLE
            binding.tvAircraftResult.visibility = View.GONE

            lifecycleScope.launch {
                val result = GodsEyeApiHub.fetchAircraftDetails(hex = query, callsign = query)
                binding.pbAircraft.visibility = View.GONE

                result.onSuccess { ac ->
                    binding.tvAircraftResult.visibility = View.VISIBLE
                    val sb = StringBuilder()
                    sb.append("AIRCRAFT INTEL REPORT\n")
                    sb.append("-----------------------------\n")
                    sb.append("REGISTRATION : ${ac.registration ?: "UNKNOWN"}\n")
                    sb.append("OPERATOR     : ${ac.operator ?: "Civilian / Unlisted"}\n")
                    sb.append("MANUFACTURER : ${ac.manufacturer ?: "N/A"}\n")
                    sb.append("MODEL / TYPE : ${ac.model ?: "N/A"} (${ac.typeCode ?: "N/A"})\n")
                    if (!ac.originAirport.isNullOrBlank() || !ac.destinationAirport.isNullOrBlank()) {
                        sb.append("ROUTE ORIGIN : ${ac.originAirport ?: "Unknown"}\n")
                        sb.append("ROUTE DEST   : ${ac.destinationAirport ?: "Unknown"}\n")
                    } else {
                        sb.append("ROUTE STATUS : Live telemetry track active\n")
                    }
                    binding.tvAircraftResult.text = sb.toString()
                }.onFailure { err ->
                    binding.tvAircraftResult.visibility = View.VISIBLE
                    binding.tvAircraftResult.text = "Lookup failed: ${err.message}"
                }
            }
        }
    }

    // ==========================================
    // 4. NOAA CYCLONES & HURRICANES
    // ==========================================
    private fun setupCyclonesSection() {
        binding.btnConnectCyclones.setOnClickListener {
            binding.pbCyclones.visibility = View.VISIBLE
            binding.tvCyclonesResult.visibility = View.GONE

            lifecycleScope.launch {
                val result = GodsEyeApiHub.fetchActiveCyclones()
                binding.pbCyclones.visibility = View.GONE
                binding.tvCyclonesResult.visibility = View.VISIBLE

                result.onSuccess { storms ->
                    if (storms.isEmpty()) {
                        binding.tvCyclonesResult.text = "NOAA NHC / CPHC: Nominal. No active tropical depressions or cyclones currently tracked in the Atlantic or Pacific monitoring basins."
                    } else {
                        val sb = StringBuilder()
                        sb.append("NOAA ACTIVE CYCLONES & STORMS (${storms.size})\n")
                        sb.append("=========================================\n")
                        storms.forEach { s ->
                            sb.append("• ${s.name.uppercase()} (${s.classification})\n")
                            sb.append("  Wind: ${s.intensityKnots} kts | Pressure: ${s.pressureMb} mb\n")
                            sb.append("  Position: ${s.latitude}°N, ${s.longitude}°W | Movement: ${s.movement}\n\n")
                        }
                        binding.tvCyclonesResult.text = sb.toString().trim()
                    }
                }.onFailure { err ->
                    binding.tvCyclonesResult.text = "NOAA Cyclones fetch error: ${err.message}"
                }
            }
        }
    }

    // ==========================================
    // 5. GDELT REGIONAL SECURITY INTELLIGENCE
    // ==========================================
    private fun setupNewsSection() {
        binding.btnConnectNews.setOnClickListener {
            binding.pbNews.visibility = View.VISIBLE
            binding.containerNews.removeAllViews()

            lifecycleScope.launch {
                val result = GodsEyeApiHub.fetchRegionalNews(query = "defense OR security OR space OR aviation", maxRecords = 8)
                binding.pbNews.visibility = View.GONE

                result.onSuccess { articles ->
                    if (articles.isEmpty()) {
                        addSimpleTextView(binding.containerNews, "No intelligence articles found.")
                    } else {
                        articles.forEach { art ->
                            val card = createInfoCard(
                                title = art.title,
                                subtitle = "Source: ${art.domain} • ${art.publishedAt}",
                                details = "Click to inspect source link",
                                accentColor = "#A855F7",
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(art.url))
                                        startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            )
                            binding.containerNews.addView(card)
                        }
                    }
                }.onFailure { err ->
                    addSimpleTextView(binding.containerNews, "Intelligence feed error: ${err.message}", "#FF4444")
                }
            }
        }
    }

    // ==========================================
    // HELPER UI FACTORIES
    // ==========================================
    private fun createInfoCard(
        title: String,
        subtitle: String,
        details: String,
        accentColor: String = "#00D4FF",
        onClick: (() -> Unit)? = null
    ): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundResource(R.drawable.bg_godseye_hud_back)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10
            }
            layoutParams = lp

            val tvTitle = TextView(requireContext()).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 12f
                paint.isFakeBoldText = true
            }

            val tvSub = TextView(requireContext()).apply {
                text = subtitle
                setTextColor(Color.parseColor(accentColor))
                textSize = 10f
                paint.isFakeBoldText = true
                setPadding(0, 4, 0, 4)
            }

            val tvDet = TextView(requireContext()).apply {
                text = details
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10.5f
            }

            addView(tvTitle)
            addView(tvSub)
            if (details.isNotBlank()) addView(tvDet)

            if (onClick != null) {
                setOnClickListener { onClick() }
            }
        }
    }

    private fun addSimpleTextView(container: LinearLayout, text: String, colorHex: String = "#94A3B8") {
        val tv = TextView(requireContext()).apply {
            this.text = text
            setTextColor(Color.parseColor(colorHex))
            textSize = 11f
            setPadding(8, 8, 8, 8)
        }
        container.addView(tv)
    }

    private fun formatIsoDate(iso: String): String {
        return try {
            iso.replace("T", " ").replace("Z", " UTC")
        } catch (_: Exception) {
            iso
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopAudioStream()
        _binding = null
    }
}
