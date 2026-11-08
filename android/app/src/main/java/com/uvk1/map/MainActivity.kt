package com.uvk1.map

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.uvk1.map.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SOURCE_SONDE = 1
        private const val TRACK_MAX_POINTS = 2000
        /** Ignore micro-jitters under ~11 m so the trail stays readable. */
        private const val TRACK_MIN_MOVE_M = 11.0
    }

    private lateinit var binding: ActivityMainBinding

    private var service: RadioLinkService? = null
    private var bound = false
    private var replaying = false

    private var meMarker: Marker? = null
    private var accuracyCircle: Polygon? = null
    private val targetMarkers = LinkedHashMap<String, Marker>()
    private val sondeTracks = LinkedHashMap<String, Polyline>()
    private val sondeTrackPoints = LinkedHashMap<String, ArrayList<GeoPoint>>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private var lastTargetPoint: GeoPoint? = null
    private val trackColors = intArrayOf(
        Color.argb(220, 230, 126, 34),
        Color.argb(220, 41, 128, 185),
        Color.argb(220, 142, 68, 173),
        Color.argb(220, 39, 174, 96),
        Color.argb(220, 192, 57, 43),
    )

    private val uiListener = object : RadioLinkService.Listener {
        override fun onReplayStart() {
            replaying = true
        }

        override fun onReplayEnd() {
            replaying = false
            if (meMarker == null) {
                lastTargetPoint?.let { point ->
                    binding.map.controller.setZoom(12.0)
                    binding.map.controller.setCenter(point)
                }
            }
            binding.map.invalidate()
            refreshLinkButtons()
        }

        override fun onStatus(message: String) {
            setStatus(message)
        }

        override fun onPhoneFix(lat: Double, lon: Double, accuracy: Float) {
            updateMeMarker(lat, lon, accuracy)
            refreshLinkButtons()
        }

        override fun onReported(
            pos: K5ViewerProtocol.ReportedPosition,
            atMs: Long,
            replay: Boolean,
        ) {
            showReported(pos, Date(atMs), replay)
        }

        override fun onConnectionChanged(connected: Boolean) {
            refreshLinkButtons()
        }

        override fun onChooseDevice(
            candidates: List<UsbSerialDriver>,
            pick: (UsbSerialDriver) -> Unit,
        ) {
            if (isFinishing || isDestroyed) {
                if (candidates.isNotEmpty()) pick(candidates.first())
                return
            }
            showDevicePicker(candidates, pick)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val link = (binder as? RadioLinkService.LocalBinder)?.getService() ?: return
            service = link
            link.setLinkMode(loadLinkMode())
            link.setListener(uiListener)
            refreshLinkButtons()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation()
        if (fine) {
            ensureService(connect = false)
            service?.startGps()
        } else if (!hasFineLocation()) {
            val msg = "GPS refusé — la carte fonctionne, pas d'inject"
            SessionLog.addStatus(msg)
            setStatus(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false
        binding.topBar.applySystemBarInsets(top = true, bottom = false, horizontal = true)
        binding.map.applyBottomInsetAsMargin()

        setupMap()
        setupLinkModeSpinner()

        binding.connectButton.setOnClickListener {
            binding.connectButton.isEnabled = false
            ensureService(connect = true)
        }
        binding.disconnectButton.setOnClickListener { service?.disconnectRadio() }
        binding.logsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        binding.quitButton.setOnClickListener { confirmQuit() }

        ensureLocationPermission()
    }

    private fun confirmQuit() {
        AlertDialog.Builder(this)
            .setTitle(R.string.quit)
            .setMessage(R.string.quit_confirm)
            .setPositiveButton(R.string.quit) { _, _ -> performQuit() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performQuit() {
        if (bound) {
            service?.setListener(null)
            unbindService(connection)
            bound = false
            service = null
        }
        stopService(Intent(this, RadioLinkService::class.java))
        finishAndRemoveTask()
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts the foreground service that owns GPS + USB while the UI is gone. */
    private fun ensureService(connect: Boolean) {
        val intent = Intent(this, RadioLinkService::class.java).apply {
            action = if (connect) RadioLinkService.ACTION_CONNECT else RadioLinkService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, intent)
            if (!bound) {
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
                bound = true
            }
        } catch (e: Exception) {
            setStatus("Impossible de démarrer le service: ${e.message}")
            refreshLinkButtons()
        }
    }

    private fun setupLinkModeSpinner() {
        val modes = LinkMode.entries
        binding.linkModeSpinner.adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            modes.map { it.label },
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        val saved = loadLinkMode()
        binding.linkModeSpinner.setSelection(modes.indexOf(saved).coerceAtLeast(0))
        binding.linkModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                val mode = modes[position]
                saveLinkMode(mode)
                service?.setLinkMode(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showDevicePicker(
        candidates: List<UsbSerialDriver>,
        pick: (UsbSerialDriver) -> Unit,
    ) {
        val labels = candidates.map { d ->
            val dev = d.device
            val name = dev.productName?.takeIf { it.isNotBlank() }
                ?: dev.deviceName?.takeIf { it.isNotBlank() }
                ?: "USB"
            "%s  (%04x:%04x)".format(name, dev.vendorId, dev.productId)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_device)
            .setItems(labels) { _, which -> pick(candidates[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun prefs() = getSharedPreferences("uvk1_map", MODE_PRIVATE)

    private fun loadLinkMode(): LinkMode {
        val raw = prefs().getString("link_mode", LinkMode.AUTO.name) ?: LinkMode.AUTO.name
        return runCatching { LinkMode.valueOf(raw) }.getOrDefault(LinkMode.AUTO)
    }

    private fun saveLinkMode(mode: LinkMode) {
        prefs().edit().putString("link_mode", mode.name).apply()
    }

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(6.0)
        binding.map.controller.setCenter(GeoPoint(46.5, 2.5))
    }

    private fun ensureLocationPermission() {
        val needed = mutableListOf<String>()
        if (!hasFineLocation()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (hasFineLocation()) {
            ensureService(connect = false)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun updateMeMarker(lat: Double, lon: Double, accuracy: Float) {
        val point = GeoPoint(lat, lon)
        val marker = meMarker ?: Marker(binding.map).also {
            it.title = "Téléphone"
            it.snippet = "Position GPS du téléphone (injectée vers la radio)"
            it.icon = markerIcon(R.drawable.ic_marker_phone)
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.setOnMarkerClickListener { m, _ ->
                m.showInfoWindow()
                true
            }
            binding.map.overlays.add(it)
            meMarker = it
            binding.map.controller.setZoom(13.0)
            binding.map.controller.setCenter(point)
        }
        marker.position = point
        marker.snippet = buildString {
            append("GPS téléphone\n")
            append("%.5f°, %.5f°".format(lat, lon))
            if (accuracy > 0f) append("\n± ${accuracy.toInt()} m")
        }

        if (accuracy > 0f) {
            val circle = accuracyCircle ?: Polygon(binding.map).also {
                it.fillPaint.color = Color.argb(30, 99, 230, 190)
                it.outlinePaint.color = Color.argb(160, 99, 230, 190)
                it.outlinePaint.strokeWidth = 2f
                binding.map.overlays.add(0, it)
                accuracyCircle = it
            }
            circle.points = Polygon.pointsAsCircle(point, accuracy.toDouble())
        }
        if (!replaying) binding.map.invalidate()
    }

    private fun showReported(pos: K5ViewerProtocol.ReportedPosition, at: Date, replay: Boolean) {
        val point = GeoPoint(pos.latDeg, pos.lonDeg)
        lastTargetPoint = point
        val isNew = !targetMarkers.containsKey(pos.label)
        val marker = targetMarkers.getOrPut(pos.label) {
            Marker(binding.map).also {
                it.icon = iconForSource(pos.source)
                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                it.setOnMarkerClickListener { m, map ->
                    map.controller.animateTo(m.position)
                    m.showInfoWindow()
                    true
                }
                binding.map.overlays.add(it)
            }
        }
        marker.position = point
        marker.icon = iconForSource(pos.source)
        marker.title = pos.label
        marker.snippet = popupSnippet(pos, at)

        if (pos.source == SOURCE_SONDE) {
            appendSondeTrack(pos.label, point)
        }

        if (!replay) setStatus("Cible ${pos.label} (${pos.sourceName})")
        if (!replay && isNew) {
            binding.map.controller.setZoom(12.0.coerceAtLeast(binding.map.zoomLevelDouble))
            binding.map.controller.animateTo(point)
            marker.showInfoWindow()
        } else if (!replay && marker.isInfoWindowShown) {
            marker.showInfoWindow()
        }
        if (!replaying) binding.map.invalidate()
    }

    private fun appendSondeTrack(label: String, point: GeoPoint) {
        val pts = sondeTrackPoints.getOrPut(label) { ArrayList() }
        val last = pts.lastOrNull()
        if (last != null && last.distanceToAsDouble(point) < TRACK_MIN_MOVE_M) {
            return
        }
        pts.add(GeoPoint(point))
        while (pts.size > TRACK_MAX_POINTS) {
            pts.removeAt(0)
        }

        val track = sondeTracks.getOrPut(label) {
            val color = trackColors[sondeTracks.size % trackColors.size]
            Polyline(binding.map).also { poly ->
                poly.outlinePaint.color = color
                poly.outlinePaint.strokeWidth = 6f
                poly.outlinePaint.isAntiAlias = true
                // Under the balloon marker so the icon stays on top
                val markerIndex = binding.map.overlays.indexOf(targetMarkers[label])
                if (markerIndex >= 0) {
                    binding.map.overlays.add(markerIndex, poly)
                } else {
                    binding.map.overlays.add(0, poly)
                }
            }
        }
        track.setPoints(ArrayList(pts))
    }

    private fun popupSnippet(pos: K5ViewerProtocol.ReportedPosition, at: Date): String =
        buildString {
            append(
                when (pos.source) {
                    1 -> "Radiosonde"
                    2 -> "EPIRB 406"
                    3 -> "Station APRS"
                    4 -> "AIS"
                    else -> "Cible"
                }
            )
            append('\n')
            append("Lat  %.5f°\n".format(pos.latDeg))
            append("Lon  %.5f°\n".format(pos.lonDeg))
            if (pos.altM != 0) append("Alt  ${pos.altM} m\n")
            append("Reçu ${timeFmt.format(at)}")
        }

    private fun iconForSource(source: Int): Drawable =
        markerIcon(
            when (source) {
                1 -> R.drawable.ic_marker_balloon
                2 -> R.drawable.ic_marker_epirb
                3 -> R.drawable.ic_marker_aprs
                else -> R.drawable.ic_marker_aprs
            }
        )

    private fun markerIcon(@DrawableRes resId: Int): Drawable {
        val d = ContextCompat.getDrawable(this, resId)!!.mutate()
        val w = (40 * resources.displayMetrics.density).toInt()
        val h = (52 * resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, w, h)
        // osmdroid uses intrinsic size for Marker; force it for vectors
        if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
            return d
        }
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun setStatus(msg: String) {
        binding.statusText.text = msg
        refreshLinkButtons()
    }

    private fun refreshLinkButtons() {
        val connected = service?.isConnected == true
        binding.disconnectButton.isEnabled = connected
        binding.connectButton.isEnabled = !connected
    }

    override fun onStart() {
        super.onStart()
        if (!bound && hasFineLocation()) ensureService(connect = false)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        binding.map.invalidate()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (bound) {
            service?.setListener(null)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
