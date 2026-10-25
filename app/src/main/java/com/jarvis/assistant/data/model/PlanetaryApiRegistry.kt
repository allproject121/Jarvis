package com.jarvis.assistant.data.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/**
 * PlanetaryApiRegistry: Comprehensive Catalog and Health Diagnostics of all
 * upstream Intelligence, Radar, Satellite, CCTV, Aviation, Transit, Infrastructure
 * and Sensor APIs integrated within JARVIS and God's Eye View.
 */
object PlanetaryApiRegistry {

    enum class ApiCategory(val displayName: String, val badgeColor: String) {
        ALL("ALL", "#00E5FF"),
        AVIATION("AVIATION", "#00E5FF"),
        MARITIME("MARITIME", "#00B0FF"),
        SPACE("SPACE", "#7C4DFF"),
        CCTV("CCTV", "#00E676"),
        WEATHER("WEATHER", "#FFD600"),
        SEISMIC("SEISMIC", "#FF3D00"),
        DISASTER("DISASTER", "#FF6D00"),
        DEFENSE("DEFENSE", "#E040FB"),
        INFRASTRUCTURE("INFRASTRUCTURE", "#26C6DA"),
        INTEL("INTEL", "#64FFDA"),
        AUDIO("AUDIO", "#FF4081"),
        TRANSIT("TRANSIT", "#40C4FF"),
        GEOSPATIAL("GEOSPATIAL", "#B2FF59")
    }

    enum class AuthRequirement(val label: String) {
        KEYLESS_FREE("100% Free / Keyless"),
        RATE_LIMITED("Keyless (Rate-Limited Upstream)"),
        BYOK_OPTIONAL("Free Default / BYOK Enhanced"),
        BYOK_REQUIRED("API Key Required")
    }

    data class ApiEntry(
        val id: String,
        val name: String,
        val category: ApiCategory,
        val provider: String,
        val endpointUrl: String,
        val shortSummary: String,
        val detailedDescriptionHindi: String,
        val detailedDescriptionEnglish: String,
        val authRequirement: AuthRequirement,
        val godsEyeRole: String,
        val voiceCommand: String,
        val targetLat: Double = 0.0,
        val targetLon: Double = 0.0,
        val targetAlt: Double = 500000.0,
        val layerId: String = "",
        var lastStatus: String = "IDLE", // "OK", "ERROR", "TIMEOUT", "IDLE"
        var lastLatencyMs: Long = -1L,
        var isExpanded: Boolean = false,
        var isRawDataVisible: Boolean = false,
        var cachedRawData: String = ""
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val APIS: List<ApiEntry> = listOf(
        ApiEntry(
            id = "usgs_earthquakes",
            name = "USGS Realtime Earthquakes",
            category = ApiCategory.SEISMIC,
            provider = "U.S. Geological Survey (USGS)",
            endpointUrl = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson",
            shortSummary = "Global seismic activity & tectonic fault rupture tracker",
            detailedDescriptionHindi = "Duniya bhar me hone wale real-time bhukamp (earthquakes), unka epicenter (latitude/longitude), magnitude (tivrata) aur depth record karta hai.",
            detailedDescriptionEnglish = "Tracks global real-time seismic events, magnitude, focal depth, and tectonic coordinates updated every 60 seconds.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Renders expanding red seismic pulse rings and magnitude badges on tectonic fault lines.",
            voiceCommand = "'Jarvis, show latest earthquakes' ya 'Bhukamp track karo'",
            targetLat = 35.6762,
            targetLon = 139.6503,
            targetAlt = 800000.0,
            layerId = "earthquakes"
        ),
        ApiEntry(
            id = "adsb_flights",
            name = "OpenSky & Community ADS-B Flights",
            category = ApiCategory.AVIATION,
            provider = "OpenSky Network / ADSB.lol",
            endpointUrl = "https://opensky-network.org/api/states/all?extended=1",
            shortSummary = "Worldwide civilian aircraft transponders, altitude & heading",
            detailedDescriptionHindi = "Aasman me ud rahe sabhi commercial aur private planes ke Mode-S/ADS-B signals se unki live position, speed aur altitude track karta hai.",
            detailedDescriptionEnglish = "Worldwide live surveillance of commercial and private aircraft using Mode-S and 1090MHz ADS-B transponder data.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Draws 3D jet aircraft models, altitude trails, callsigns, and speed vectors in realtime.",
            voiceCommand = "'Jarvis, scan air traffic' ya 'Hawa me ud rahe planes dikhao'",
            targetLat = 40.7128,
            targetLon = -74.0060,
            targetAlt = 220000.0,
            layerId = "flights"
        ),
        ApiEntry(
            id = "adsb_mil",
            name = "ADS-B Military Fleet Tracking",
            category = ApiCategory.DEFENSE,
            provider = "ADSB.lol Military Transponder Network",
            endpointUrl = "https://api.adsb.lol/v2/mil",
            shortSummary = "Global military fighters, aerial tankers & reconnaissance aircraft",
            detailedDescriptionHindi = "Air Force fighter jets, surveillance aircraft (AWACS) aur military transport planes ke transponders ko isolate karke track karta hai.",
            detailedDescriptionEnglish = "Dedicated military transponder stream tracking NATO, allied, and defense air wings worldwide.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Renders strategic defense flight paths with tactical stealth/tactical radar signatures.",
            voiceCommand = "'Jarvis, track military aircraft' ya 'Military jets scan karo'",
            targetLat = 36.0,
            targetLon = -115.0,
            targetAlt = 350000.0,
            layerId = "flights"
        ),
        ApiEntry(
            id = "adsbdb_specs",
            name = "ADSBdb Aircraft Specs & Flight Routes",
            category = ApiCategory.AVIATION,
            provider = "ADSBdb Database Engine",
            endpointUrl = "https://api.adsbdb.com/v0/callsign/SWA123",
            shortSummary = "Decodes callsigns into airlines, origin & destination airports",
            detailedDescriptionHindi = "Kisi bhi flight ke callsign (e.g. AIC101) se airline, aircraft model (Boeing/Airbus) aur kahan se kahan ud raha hai (origin -> destination) decode karta hai.",
            detailedDescriptionEnglish = "Enriches raw ICAO/callsign data with aircraft operator, airframe model, registration, and flight route corridor.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Populates the Cockpit HUD card with airline livery, aircraft specs, and flight path origins.",
            voiceCommand = "'Jarvis, who owns this aircraft?' ya 'Ye flight kahan ja rahi hai?'",
            targetLat = 32.8998,
            targetLon = -97.0403,
            targetAlt = 150000.0,
            layerId = "flights"
        ),
        ApiEntry(
            id = "launch_library",
            name = "Launch Library 2 Space Missions",
            category = ApiCategory.SPACE,
            provider = "The Space Devs (v2.3)",
            endpointUrl = "https://ll.thespacedevs.com/2.3.0/launches/?limit=50&mode=detailed",
            shortSummary = "Orbital rocket launches (SpaceX, ISRO, NASA, ESA, Roscosmos)",
            detailedDescriptionHindi = "ISRO (Sriharikota), SpaceX (Falcon 9/Starship), NASA aur duniya bhar ke space missions ka live countdown, pad location aur mission payload details batata hai.",
            detailedDescriptionEnglish = "Comprehensive spaceflight launch schedule, launchpad GPS coordinates, booster telemetry, and mission descriptions.",
            authRequirement = AuthRequirement.RATE_LIMITED,
            godsEyeRole = "Displays orbital launchpads on the 3D globe with mission countdown timers and target orbits.",
            voiceCommand = "'Jarvis, upcoming rocket launches' ya 'Agla space launch kab hai?'",
            targetLat = 28.5729,
            targetLon = -80.6490,
            targetAlt = 300000.0,
            layerId = "launches"
        ),
        ApiEntry(
            id = "celestrak_satellites",
            name = "CelesTrak NORAD Satellite Orbits",
            category = ApiCategory.SPACE,
            provider = "CelesTrak / Dr. T.S. Kelso",
            endpointUrl = "https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=tle",
            shortSummary = "Live SGP4 orbital propagation for 10,000+ active satellites & ISS",
            detailedDescriptionHindi = "Earth ke orbit me ghum rahe International Space Station (ISS), Starlink aur spy satellites ke Two-Line Element sets (TLE) se exact orbital path calculate karta hai.",
            detailedDescriptionEnglish = "Provides Keplerian two-line element sets for mathematical orbit propagation of active satellites and the ISS.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Renders real-time 3D orbital rings, orbital footprint cones, and ground-track nadirs.",
            voiceCommand = "'Jarvis, locate ISS' ya 'Satellites view on karo'",
            targetLat = 0.0,
            targetLon = 0.0,
            targetAlt = 8000000.0,
            layerId = "satellites"
        ),
        ApiEntry(
            id = "solar_weather",
            name = "NOAA SWPC Solar Space Weather",
            category = ApiCategory.SPACE,
            provider = "NOAA Space Weather Prediction Center (SWPC)",
            endpointUrl = "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json",
            shortSummary = "Real-time Planetary K-index, solar flares & geomagnetic storms",
            detailedDescriptionHindi = "Suraj ke solar flares, coronal mass ejections (CME) aur earth ke magnetic field me hone wale geomagnetic toofano ka live index batata hai.",
            detailedDescriptionEnglish = "Real-time 1-minute Planetary K-index, solar radiation, geomagnetic storm intensity, and auroral activity.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Renders space weather radiation halos and aurora borealis ionization belts.",
            voiceCommand = "'Jarvis, solar storm status' ya 'Solar flares check karo'",
            targetLat = 65.0,
            targetLon = 25.0,
            targetAlt = 10000000.0,
            layerId = "solar"
        ),
        ApiEntry(
            id = "radio_browser",
            name = "Radio Browser Planetary Audio",
            category = ApiCategory.AUDIO,
            provider = "Radio Browser Community",
            endpointUrl = "https://de1.api.radio-browser.info/json/stations/topclick/750",
            shortSummary = "30,000+ live broadcast radio stations across 180+ countries",
            detailedDescriptionHindi = "Duniya ke har shahar aur desh ke live AM/FM/Internet radio streams ko connect karta hai, jisse aap kisi bhi shehar ka live radio sun sakte hain.",
            detailedDescriptionEnglish = "Community-driven directory of over 30,000 live streaming broadcast radio stations filterable by country, genre, and bitrate.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Ties live audio streams to physical geographic coordinates; tapping a station plays live audio in JARVIS.",
            voiceCommand = "'Jarvis, play London radio' ya 'Live radio play karo'",
            targetLat = 51.5074,
            targetLon = -0.1278,
            targetAlt = 500000.0,
            layerId = "radio"
        ),
        ApiEntry(
            id = "noaa_cyclones",
            name = "NOAA NHC Tropical Cyclones & Hurricanes",
            category = ApiCategory.WEATHER,
            provider = "NOAA National Hurricane Center (NHC / CPHC)",
            endpointUrl = "https://www.nhc.noaa.gov/CurrentStorms.json",
            shortSummary = "Active cyclones, typhoons, wind speed (knots) & track cones",
            detailedDescriptionHindi = "Samundar me uthne wale khatarnak cyclones, hurricanes aur typhoons ki live category, wind speed aur aage badhne ka rasta (track cone) track karta hai.",
            detailedDescriptionEnglish = "Real-time tropical cyclone tracking, storm category, central pressure (mb), wind speed (knots), and forecast cones.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Draws rotating hurricane storm vortices and predictive cone-of-uncertainty ribbons on oceans.",
            voiceCommand = "'Jarvis, active hurricanes' ya 'Cyclone update do'",
            targetLat = 24.0,
            targetLon = -75.0,
            targetAlt = 1200000.0,
            layerId = "cyclones"
        ),
        ApiEntry(
            id = "rainviewer_radar",
            name = "RainViewer Global Weather Radar",
            category = ApiCategory.WEATHER,
            provider = "RainViewer Doppler Network",
            endpointUrl = "https://api.rainviewer.com/public/weather-maps.json",
            shortSummary = "Worldwide real-time doppler precipitation & cloud radar",
            detailedDescriptionHindi = "Pure vishwa ke doppler radars se baarish, baraf, baadal aur toofan ki live intensity satellite map par overlay karta hai.",
            detailedDescriptionEnglish = "High-resolution mosaic of ground radar stations providing live and 2-hour forecast precipitation imagery.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Drapes animated color-coded doppler rain/snow reflectivity tiles directly over 3D terrain.",
            voiceCommand = "'Jarvis, show weather radar' ya 'Baarish ka radar dikhao'",
            targetLat = 28.6139,
            targetLon = 77.2090,
            targetAlt = 450000.0,
            layerId = "weather"
        ),
        ApiEntry(
            id = "nowcoast_lightning",
            name = "NOAA nowCOAST Live Lightning Density",
            category = ApiCategory.WEATHER,
            provider = "NOAA / Vaisala NLDN GLD360",
            endpointUrl = "https://nowcoast.noaa.gov/geoserver/observations/lightning_detection/ows?service=WMS&version=1.1.1&request=GetCapabilities",
            shortSummary = "15-minute accumulated ground and cloud lightning strike density",
            detailedDescriptionHindi = "Asli samay me kahan kahan aakashiya bijli (lightning strikes) giri hain unka density grid aur severe thunderstorm cells record karta hai.",
            detailedDescriptionEnglish = "15-minute accumulated lightning strike density on an 8 km grid, detecting cloud-to-ground flash discharges.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Renders energetic cyan/purple lightning flash clusters over active thunderstorm cells.",
            voiceCommand = "'Jarvis, show lightning strikes' ya 'Bijli kahan gir rahi hai?'",
            targetLat = 32.0,
            targetLon = -90.0,
            targetAlt = 1200000.0,
            layerId = "lightning"
        ),
        ApiEntry(
            id = "open_meteo",
            name = "Open-Meteo Atmospheric Forecast",
            category = ApiCategory.WEATHER,
            provider = "Open-Meteo European Weather Engine",
            endpointUrl = "https://api.open-meteo.com/v1/forecast?latitude=28.61&longitude=77.20&current=temperature_2m,weather_code",
            shortSummary = "Hyper-local temperature, wind speed, pressure & humidity",
            detailedDescriptionHindi = "Kisi bhi coordinate par current tapman (temperature), hawa ki raftaar, atmospheric pressure aur mausam ki sthiti calculate karta hai.",
            detailedDescriptionEnglish = "Free, open-source weather forecasting API utilizing national meteorological models (ECMWF, DWD, NOAA).",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Powers cockpit weather telemetry and ambient atmospheric fog/lighting shaders.",
            voiceCommand = "'Jarvis, current weather' ya 'Mausam kaisa hai?'",
            targetLat = 28.61,
            targetLon = 77.20,
            targetAlt = 50000.0,
            layerId = "weather"
        ),
        ApiEntry(
            id = "noaa_gfs_wind",
            name = "NOAA GFS & ECMWF Global Wind Vectors",
            category = ApiCategory.WEATHER,
            provider = "NOAA Global Forecast System / ECMWF",
            endpointUrl = "https://noaa-gfs-bdp-pds.s3.amazonaws.com/",
            shortSummary = "Global 10m wind particle streamlines and jet streams",
            detailedDescriptionHindi = "Pure globe par chalne wali hawa ke jet streams aur ocean wind currents ko animated particle streamlines ke roop me dikhata hai.",
            detailedDescriptionEnglish = "Global gridded 0.25-degree wind velocity forecast vectors driving dynamic particle animations on the globe.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Powers the animated particle wind streams blowing across oceans and mountain ranges.",
            voiceCommand = "'Jarvis, toggle wind vectors' ya 'Hawa ki disha dikhao'",
            targetLat = 15.0,
            targetLon = 70.0,
            targetAlt = 3000000.0,
            layerId = "wind"
        ),
        ApiEntry(
            id = "cables_subsea",
            name = "Global Subsea Internet Cables",
            category = ApiCategory.INFRASTRUCTURE,
            provider = "TeleGeography & OpenStreetMap",
            endpointUrl = "https://www.submarinecablemap.com/",
            shortSummary = "Submarine fiber optic internet cables & coastal landing stations",
            detailedDescriptionHindi = "Samundar ki gehrai me bichhe fiber optic submarine internet cables aur unke landing stations ka worldwide network dikhata hai.",
            detailedDescriptionEnglish = "Comprehensive geographic vector network of active and planned undersea telecommunications cables connecting continents.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Draws illuminated glowing fiber optic conduits snaking across oceans and landing stations.",
            voiceCommand = "'Jarvis, show undersea cables' ya 'Samundar ke internet cable dikhao'",
            targetLat = 30.0,
            targetLon = -40.0,
            targetAlt = 4500000.0,
            layerId = "cables"
        ),
        ApiEntry(
            id = "datacenters_infra",
            name = "Hyperscale Cloud Data Centers",
            category = ApiCategory.INFRASTRUCTURE,
            provider = "OpenStreetMap Infrastructure Registry",
            endpointUrl = "https://overpass-api.de/api/interpreter?data=[out:json];nwr[\"telecom\"=\"data_center\"];out center tags 50;",
            shortSummary = "Global AWS, Google Cloud, Azure & Equinix server facilities",
            detailedDescriptionHindi = "Duniya bhar ke core hyperscale data centers, server farms aur international peering hubs ko locate karta hai.",
            detailedDescriptionEnglish = "Overpass query engine mapping major commercial cloud server facilities, carrier hotels, and interconnection hubs.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Displays glowing cyber tower beacons and server capacity markers over cloud clusters.",
            voiceCommand = "'Jarvis, scan data centers' ya 'Data centers kahan hain?'",
            targetLat = 38.9072,
            targetLon = -77.0369,
            targetAlt = 200000.0,
            layerId = "datacenters"
        ),
        ApiEntry(
            id = "dams_power",
            name = "Hydroelectric Dams & Power Grids",
            category = ApiCategory.INFRASTRUCTURE,
            provider = "OpenStreetMap Global Registry",
            endpointUrl = "https://overpass-api.de/api/interpreter?data=[out:json];nwr[\"waterway\"=\"dam\"];out center tags 50;",
            shortSummary = "Major hydroelectric reservoirs, dams & power stations",
            detailedDescriptionHindi = "Duniya ke bade hydroelectric dams, reservoirs aur energy generation grid infrastructure ko highlight karta hai.",
            detailedDescriptionEnglish = "Critical infrastructure mapping of major hydroelectric dams, retention barriers, and renewable generation plants.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Marks strategic civil reservoirs and hydroelectric generation points.",
            voiceCommand = "'Jarvis, locate major dams' ya 'Dams map karo'",
            targetLat = 36.0156,
            targetLon = -114.7378,
            targetAlt = 60000.0,
            layerId = "dams"
        ),
        ApiEntry(
            id = "transit_mbta",
            name = "Urban Public Transit (GTFS-RT MBTA)",
            category = ApiCategory.TRANSIT,
            provider = "Massachusetts Bay Transportation Authority (MBTA)",
            endpointUrl = "https://cdn.mbta.com/realtime/VehiclePositions.json",
            shortSummary = "Real-time municipal bus, subway & commuter rail positions",
            detailedDescriptionHindi = "City transit me chal rahi sabhi public buses, commuter trains aur subways ki live GPS speed aur bearing track karta hai.",
            detailedDescriptionEnglish = "Official GTFS-Realtime vehicle position feed streaming live bus and train updates across metropolitan transit grids.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Draws real-time moving bus and metro dots along city transit corridors.",
            voiceCommand = "'Jarvis, live city transit' ya 'Buses track karo'",
            targetLat = 42.3601,
            targetLon = -71.0589,
            targetAlt = 45000.0,
            layerId = "transit"
        ),
        ApiEntry(
            id = "nasa_firms",
            name = "NASA FIRMS Wildfire Hotspots",
            category = ApiCategory.DISASTER,
            provider = "NASA EOSDIS / VIIRS & MODIS Satellites",
            endpointUrl = "https://firms.modaps.eosdis.nasa.gov/",
            shortSummary = "Near real-time thermal anomalies & forest fire detections",
            detailedDescriptionHindi = "NASA ke satellite thermal sensors se jungle ki aag (forest fires) aur thermal hotspots ko 375m accuracy ke sath detect karta hai.",
            detailedDescriptionEnglish = "Satellite thermal sensor detections identifying active wildland fires, agricultural burns, and gas flaring worldwide.",
            authRequirement = AuthRequirement.BYOK_OPTIONAL,
            godsEyeRole = "Places blazing fire markers and heat-index radiometry on forested regions.",
            voiceCommand = "'Jarvis, scan active wildfires' ya 'Jungle ki aag detect karo'",
            targetLat = -15.0,
            targetLon = -55.0,
            targetAlt = 800000.0,
            layerId = "fires"
        ),
        ApiEntry(
            id = "nifc_wildfires",
            name = "NIFC WFIGS Fire Incident Perimeters",
            category = ApiCategory.DISASTER,
            provider = "National Interagency Fire Center (NIFC)",
            endpointUrl = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query?where=1%3D1&outFields=*&f=geojson",
            shortSummary = "Official containment boundaries & incident commands",
            detailedDescriptionHindi = "Fire-fighters dwara verify kiye gaye active aag ke perimeters, containment percentage (%) aur danger zones draw karta hai.",
            detailedDescriptionEnglish = "Interagency wildland fire perimeters detailing containment percentage, incident name, and total acreage burned.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Draws red hazard containment polygons on the 3D landscape.",
            voiceCommand = "'Jarvis, fire containment boundaries' ya 'Aag ka perimeter dikhao'",
            targetLat = 37.0,
            targetLon = -120.0,
            targetAlt = 400000.0,
            layerId = "fires"
        ),
        ApiEntry(
            id = "gdelt_news",
            name = "GDELT Project Geopolitical Intel",
            category = ApiCategory.INTEL,
            provider = "GDELT Project DOC 2.0 API",
            endpointUrl = "https://api.gdeltproject.org/api/v2/doc/doc?query=space&mode=artlist&maxrecords=5&format=json",
            shortSummary = "Real-time global geopolitical, security & defense event news",
            detailedDescriptionHindi = "100+ bhashaon ke international news media ko scan karke geopolitical events, defense conflicts aur strategic samachar geocode karta hai.",
            detailedDescriptionEnglish = "Monitors the world's broadcast, print, and web news in over 100 languages to identify physical security and geopolitical events.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Positions glowing intel pulse beacons over cities experiencing major geopolitical developments.",
            voiceCommand = "'Jarvis, regional security news' ya 'Geopolitical intel batao'",
            targetLat = 48.8566,
            targetLon = 2.3522,
            targetAlt = 300000.0,
            layerId = "news"
        ),
        ApiEntry(
            id = "osm_military",
            name = "OpenStreetMap Strategic Military Bases",
            category = ApiCategory.DEFENSE,
            provider = "OpenStreetMap Overpass Engine",
            endpointUrl = "https://overpass-api.de/api/interpreter?data=[out:json];nwr[\"military\"];out center tags 50;",
            shortSummary = "Global airfields, naval bases, radar ranges & defense garrisons",
            detailedDescriptionHindi = "World bhar ke air-force bases, naval ports, missile test sites aur strategic defense installations ki geographic boundaries plot karta hai.",
            detailedDescriptionEnglish = "Overpass query engine extracting strategic military installations, air bases, naval harbors, and defense perimeters.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Draws strategic shield icons and security perimeters on global defense installations.",
            voiceCommand = "'Jarvis, show military bases' ya 'Fauji base scan karo'",
            targetLat = 32.7157,
            targetLon = -117.1611,
            targetAlt = 70000.0,
            layerId = "military"
        ),
        ApiEntry(
            id = "cctv_london",
            name = "London TfL JamCams Live Network",
            category = ApiCategory.CCTV,
            provider = "Transport for London (TfL)",
            endpointUrl = "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.06502.jpg",
            shortSummary = "Live traffic & landmark surveillance cameras in London, UK",
            detailedDescriptionHindi = "London ke Trafalgar Square, Piccadilly, Tower Bridge aur highways ke live cameras se har 10 second me direct live frame stream karta hai.",
            detailedDescriptionEnglish = "Hundreds of real-time municipal traffic and security surveillance cameras across Greater London and key landmarks.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Opens live street-level HUD camera feed in the surveillance dock.",
            voiceCommand = "'Jarvis, open London CCTV' ya 'London camera on karo'",
            targetLat = 51.5080,
            targetLon = -0.1281,
            targetAlt = 1500.0,
            layerId = "cctv"
        ),
        ApiEntry(
            id = "cctv_austin",
            name = "Texas TxDOT & Austin Live CCTV",
            category = ApiCategory.CCTV,
            provider = "Texas Dept of Transportation (TxDOT ITS)",
            endpointUrl = "https://cctv.austinmobility.io/image/354.jpg",
            shortSummary = "Municipal highway and intersection cameras across Texas",
            detailedDescriptionHindi = "Texas DOT aur Austin ke highway cameras ke base64 snapshots ko live decode karke live street traffic dikhata hai.",
            detailedDescriptionEnglish = "High-definition municipal traffic cameras throughout Austin and Texas highway corridors with automated Base64 decoding.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Provides real-time highway surveillance monitoring in God's Eye View.",
            voiceCommand = "'Jarvis, open Texas CCTV' ya 'Austin camera check karo'",
            targetLat = 30.2672,
            targetLon = -97.7431,
            targetAlt = 1800.0,
            layerId = "cctv"
        ),
        ApiEntry(
            id = "cctv_caltrans",
            name = "Caltrans California State CCTV",
            category = ApiCategory.CCTV,
            provider = "California Dept of Transportation",
            endpointUrl = "https://cwwp2.dot.ca.gov/data/d11/cctv/",
            shortSummary = "San Diego, Los Angeles & San Francisco highway cameras",
            detailedDescriptionHindi = "California state ke I-5, Route 54 aur interstates ke real-time cameras se live traffic monitor karta hai.",
            detailedDescriptionEnglish = "Statewide traffic surveillance network spanning California's major freeways and urban expressways.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Live highway surveillance feed in the Cockpit CCTV player.",
            voiceCommand = "'Jarvis, California CCTV feed' ya 'San Diego camera dikhao'",
            targetLat = 32.7300,
            targetLon = -117.1650,
            targetAlt = 2000.0,
            layerId = "cctv"
        ),
        ApiEntry(
            id = "cctv_hongkong",
            name = "Hong Kong Transport Department Live",
            category = ApiCategory.CCTV,
            provider = "Government of Hong Kong (GovHK)",
            endpointUrl = "https://tdcctv.data.one.gov.hk/H107F.JPG",
            shortSummary = "Dense Asian urban streetscapes & harbor crossings",
            detailedDescriptionHindi = "Hong Kong ke Connaught Road, Cross Harbour Tunnel aur dense city junctions ke live traffic cameras stream karta hai.",
            detailedDescriptionEnglish = "Real-time government street cameras monitoring Kowloon and Hong Kong Island transit corridors.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Provides live surveillance coverage of East Asian transit hubs.",
            voiceCommand = "'Jarvis, show Hong Kong camera' ya 'Hong Kong CCTV dekho'",
            targetLat = 22.2855,
            targetLon = 114.1577,
            targetAlt = 2200.0,
            layerId = "cctv"
        ),
        ApiEntry(
            id = "cctv_finland",
            name = "Finland Arctic Weathercams",
            category = ApiCategory.CCTV,
            provider = "Fintraffic Digitraffic Finland",
            endpointUrl = "https://weathercam.digitraffic.fi/C1455101.jpg",
            shortSummary = "Arctic Circle E75 highway cameras, snow & Northern Lights",
            detailedDescriptionHindi = "Finland ke Arctic region, Lapland aur E75 highway ke road weathercams se live barf aur mausam ki tasveerein dikhata hai.",
            detailedDescriptionEnglish = "High-latitude road weather stations across Finland, Lapland, and the Arctic Circle monitoring snow and road ice.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Live sub-zero snow and road conditions monitor in the tactical feed dock.",
            voiceCommand = "'Jarvis, open Arctic camera' ya 'Finland weathercam dikhao'",
            targetLat = 68.9059,
            targetLon = 27.0288,
            targetAlt = 2500.0,
            layerId = "cctv"
        ),
        ApiEntry(
            id = "cctv_norway",
            name = "Norway Statens Vegvesen Road Feeds",
            category = ApiCategory.CCTV,
            provider = "Statens Vegvesen Norway",
            endpointUrl = "https://kamera.atlas.vegvesen.no/api/images/3000051_1",
            shortSummary = "Fjord crossings, mountain passes & Nordic tunnels",
            detailedDescriptionHindi = "Norway ke fjords, mountain tunnels aur Oslo-Bergen highways ke live cameras ko realtime feed karta hai.",
            detailedDescriptionEnglish = "Norwegian public road administration cameras documenting fjord crossings, bridges, and mountain conditions.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Nordic tactical surveillance channel in God's Eye View.",
            voiceCommand = "'Jarvis, Norway CCTV feed' ya 'Norway camera check karo'",
            targetLat = 59.9139,
            targetLon = 10.7522,
            targetAlt = 3000.0,
            layerId = "cctv"
        ),
        ApiEntry(
            id = "cctv_calgary",
            name = "Calgary City Traffic Cam Network",
            category = ApiCategory.CCTV,
            provider = "City of Calgary Open Data",
            endpointUrl = "https://trafficcam.calgary.ca/loc222.jpg",
            shortSummary = "Canadian downtown urban & Bow River bridge cameras",
            detailedDescriptionHindi = "Canada ke Calgary city ke core downtown, expressways aur Bow River bridges ke live cameras stream karta hai.",
            detailedDescriptionEnglish = "Municipal road camera infrastructure for the City of Calgary, Alberta, covering all four city quadrants.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "North American transit corridor camera coverage.",
            voiceCommand = "'Jarvis, Calgary CCTV' ya 'Calgary traffic camera dikhao'",
            targetLat = 51.0447,
            targetLon = -114.0719,
            targetAlt = 2500.0,
            layerId = "cctv"
        ),
        ApiEntry(
            id = "gbfs_micromobility",
            name = "GBFS Global Bikeshare & Micromobility",
            category = ApiCategory.TRANSIT,
            provider = "General Bikeshare Feed Specification (GBFS)",
            endpointUrl = "https://gbfs.bcycle.com/bcycle_austin/gbfs.json",
            shortSummary = "Real-time electric bike, scooter & dock availability",
            detailedDescriptionHindi = "City me chalne wali electric bikes aur rental scooters ki dock availability aur battery level live track karta hai.",
            detailedDescriptionEnglish = "Standardized open data feed for shared micromobility systems including station dock capacity and vehicle GPS locations.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Renders micromobility dock markers and vehicle clusters on city blocks.",
            voiceCommand = "'Jarvis, find nearby rental bikes' ya 'Bikes kahan hain?'",
            targetLat = 30.2672,
            targetLon = -97.7431,
            targetAlt = 5000.0,
            layerId = "transit"
        ),
        ApiEntry(
            id = "photon_geocoding",
            name = "Komoot Photon Geocoding Engine",
            category = ApiCategory.GEOSPATIAL,
            provider = "Photon OpenStreetMap Search",
            endpointUrl = "https://photon.komoot.io/api/?q=London&limit=1",
            shortSummary = "Instant place, address & coordinates search without API keys",
            detailedDescriptionHindi = "Kisi bhi shahar, landmark ya address ke exact GPS coordinates (latitude/longitude) turant bina kisi Google key ke search karta hai.",
            detailedDescriptionEnglish = "High-speed keyless geocoder powered by OpenStreetMap and Elasticsearch for global place name and address resolution.",
            authRequirement = AuthRequirement.KEYLESS_FREE,
            godsEyeRole = "Powers the God's Eye location search bar and camera jump coordinates.",
            voiceCommand = "'Jarvis, fly to Tokyo' ya 'New York par zoom karo'",
            targetLat = 51.5074,
            targetLon = -0.1278,
            targetAlt = 20000.0,
            layerId = "places"
        )
    )

    /**
     * Executes a fast live HTTP ping to verify real-time status and latency.
     * Returns Pair(HttpCode, LatencyMs).
     */
    suspend fun testApiPing(api: ApiEntry): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(api.endpointUrl)
                .header("User-Agent", "Jarvis-PlanetaryDiagnostics/1.0 (+https://github.com/bilawalsidhu/gods-eye-view)")
                .build()
            val resp = httpClient.newCall(req).execute()
            val elapsed = System.currentTimeMillis() - start
            val code = resp.code
            resp.close()
            api.lastStatus = if (resp.isSuccessful) "OK" else "HTTP $code"
            api.lastLatencyMs = elapsed
            Pair(code, elapsed)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            api.lastStatus = "TIMEOUT / ERR"
            api.lastLatencyMs = elapsed
            Pair(0, elapsed)
        }
    }

    /**
     * Fetches a formatted snippet (up to 1800 chars) of raw JSON data from the API endpoint.
     */
    suspend fun fetchRawDataSnippet(api: ApiEntry): String = withContext(Dispatchers.IO) {
        if (api.cachedRawData.isNotBlank()) return@withContext api.cachedRawData
        try {
            val req = Request.Builder()
                .url(api.endpointUrl)
                .header("User-Agent", "Jarvis-PlanetaryDiagnostics/1.0")
                .header("Accept", "application/json, text/plain")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext "HTTP ${resp.code}: ${resp.message}"
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return@withContext "Empty response payload"

            val formatted = try {
                val tokener = JSONTokener(body).nextValue()
                when (tokener) {
                    is JSONObject -> tokener.toString(2)
                    is JSONArray -> tokener.toString(2)
                    else -> body
                }
            } catch (_: Exception) {
                body
            }

            val snippet = if (formatted.length > 2000) formatted.take(2000) + "\n... [TRUNCATED FOR DISPLAY]" else formatted
            api.cachedRawData = snippet
            snippet
        } catch (e: Exception) {
            "Connection Error: ${e.message}"
        }
    }
}
