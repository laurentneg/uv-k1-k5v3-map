package com.uvk1.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.uvk1.map.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var radio: UsbRadioLink
    private lateinit var phoneGps: PhoneGps

    private var meMarker: Marker? = null
    private var accuracyCircle: Polygon? = null
    private val targetMarkers = LinkedHashMap<String, Marker>()
    private val targetLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            phoneGps.start()
        } else {
            setStatus("GPS refusé — la carte fonctionne, pas d'inject")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()

        radio = UsbRadioLink(
            context = this,
            onStatus = { msg -> runOnUiThread { setStatus(msg) } },
            onReported = { pos -> runOnUiThread { onReported(pos) } },
        )
        radio.register()

        phoneGps = PhoneGps(
            context = this,
            onFix = { lat, lon, accuracy, latCas, lonCas ->
                runOnUiThread { onPhoneFix(lat, lon, accuracy, latCas, lonCas) }
            },
            onStatus = { msg -> runOnUiThread { setStatus(msg) } },
        )

        binding.connectButton.setOnClickListener { radio.connect() }
        binding.disconnectButton.setOnClickListener { radio.disconnect() }

        ensureLocationPermission()
    }

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(6.0)
        binding.map.controller.setCenter(GeoPoint(46.5, 2.5))
    }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            phoneGps.start()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    private fun onPhoneFix(lat: Double, lon: Double, accuracy: Float, latCas: Int, lonCas: Int) {
        updateMeMarker(lat, lon, accuracy)
        if (radio.isConnected) {
            radio.sendGps(latCas, lonCas)
            if (accuracy >= 0f) {
                setStatus("GPS → radio (${"%.5f".format(lat)}, ${"%.5f".format(lon)})")
            }
        } else if (accuracy >= 0f) {
            setStatus("GPS OK — connectez la radio")
        }
        binding.disconnectButton.isEnabled = radio.isConnected
        binding.connectButton.isEnabled = !radio.isConnected
    }

    private fun updateMeMarker(lat: Double, lon: Double, accuracy: Float) {
        val point = GeoPoint(lat, lon)
        val marker = meMarker ?: Marker(binding.map).also {
            it.title = "Téléphone"
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            binding.map.overlays.add(it)
            meMarker = it
            binding.map.controller.setZoom(13.0)
            binding.map.controller.setCenter(point)
        }
        marker.position = point

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
        binding.map.invalidate()
    }

    private fun onReported(pos: K5ViewerProtocol.ReportedPosition) {
        val point = GeoPoint(pos.latDeg, pos.lonDeg)
        val marker = targetMarkers.getOrPut(pos.label) {
            Marker(binding.map).also {
                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.map.overlays.add(it)
            }
        }
        marker.position = point
        marker.title = pos.label
        marker.snippet = "${pos.sourceName} · ${"%.5f".format(pos.latDeg)}, ${"%.5f".format(pos.lonDeg)}" +
            if (pos.altM != 0) " · ${pos.altM} m" else ""

        val line = "${timeFmt.format(Date())} · ${pos.label} · ${pos.sourceName} · " +
            "${"%.5f".format(pos.latDeg)}, ${"%.5f".format(pos.lonDeg)}" +
            if (pos.altM != 0) " · ${pos.altM} m" else ""
        targetLines.addFirst(line)
        while (targetLines.size > 40) targetLines.removeLast()
        binding.targetList.text = targetLines.joinToString("\n")
        setStatus("Cible ${pos.label} reçue")
        binding.map.invalidate()
    }

    private fun setStatus(msg: String) {
        binding.statusText.text = msg
        binding.disconnectButton.isEnabled = radio.isConnected
        binding.connectButton.isEnabled = !radio.isConnected
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        phoneGps.stop()
        radio.unregister()
        super.onDestroy()
    }
}
