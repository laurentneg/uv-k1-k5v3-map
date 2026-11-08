package com.uvk1.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Keeps GPS injection and USB RX alive while the activity is stopped.
 * A foreground notification is required on Android 14+ for background location
 * and for a USB host connection.
 */
class RadioLinkService : Service() {

    interface Listener {
        fun onReplayStart() {}
        fun onReplayEnd() {}
        fun onStatus(message: String) {}
        fun onPhoneFix(lat: Double, lon: Double, accuracy: Float) {}
        fun onReported(pos: K5ViewerProtocol.ReportedPosition, atMs: Long, replay: Boolean) {}
        fun onConnectionChanged(connected: Boolean) {}
        fun onChooseDevice(
            candidates: List<UsbSerialDriver>,
            pick: (UsbSerialDriver) -> Unit,
        ) {}
    }

    private data class PhoneFix(val lat: Double, val lon: Double, val accuracy: Float)
    private data class ReceivedTarget(
        val position: K5ViewerProtocol.ReportedPosition,
        val atMs: Long,
    )

    inner class LocalBinder : Binder() {
        fun getService(): RadioLinkService = this@RadioLinkService
    }

    private val binder = LocalBinder()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private val history = ArrayDeque<ReceivedTarget>()

    private lateinit var radio: UsbRadioLink
    private lateinit var phoneGps: PhoneGps
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uvk1map:radio")
            .apply { setReferenceCounted(false) }
    }

    private var listener: Listener? = null
    private var pendingChoice: Pair<List<UsbSerialDriver>, (UsbSerialDriver) -> Unit>? = null
    private var lastStatus: String = ""
    private var lastLogged: String? = null
    private var lastPhone: PhoneFix? = null
    private var lastTargetSummary: String? = null
    private var usbSession = false
    private var foreground = false
    private var released = false
    private var detachRegistered = false
    private var lastNotifAt = 0L

    private val detachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED &&
                ::radio.isInitialized && radio.isConnected
            ) {
                radio.disconnect()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        lastStatus = getString(R.string.status_idle)
        createChannel()
        radio = UsbRadioLink(
            context = applicationContext,
            onStatus = { publishStatus(it) },
            onReported = { onTarget(it) },
            onConnectionChanged = { onLinkChanged(it) },
            onChooseDevice = { candidates, pick ->
                val current = listener
                if (current != null) current.onChooseDevice(candidates, pick)
                else pendingChoice = candidates to pick
            },
        )
        radio.linkMode = loadLinkMode()
        radio.register()
        phoneGps = PhoneGps(
            context = applicationContext,
            onFix = { lat, lon, accuracy, latCas, lonCas ->
                onPhoneFix(lat, lon, accuracy, latCas, lonCas)
            },
            onStatus = { publishStatus(it) },
        )
        registerDetach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONNECT) {
            usbSession = true
        }
        startAsForeground()
        phoneGps.start()
        if (intent?.action == ACTION_CONNECT && ::radio.isInitialized && !radio.isConnected) {
            radio.connect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    fun setListener(l: Listener?) {
        listener = l
        if (l != null && !released) {
            replay(l)
            pendingChoice?.let { (candidates, pick) ->
                pendingChoice = null
                l.onChooseDevice(candidates, pick)
            }
        }
    }

    fun setLinkMode(mode: LinkMode) {
        if (::radio.isInitialized) radio.linkMode = mode
    }

    fun startGps() {
        if (released) return
        startAsForeground()
        if (::phoneGps.isInitialized) phoneGps.start()
    }

    fun disconnectRadio() {
        if (released || !::radio.isInitialized) return
        radio.disconnect()
    }

    val isConnected: Boolean
        get() = ::radio.isInitialized && radio.isConnected

    private fun replay(l: Listener) {
        l.onReplayStart()
        l.onStatus(lastStatus)
        lastPhone?.let { l.onPhoneFix(it.lat, it.lon, it.accuracy) }
        for (item in history) {
            l.onReported(item.position, item.atMs, true)
        }
        l.onConnectionChanged(isConnected)
        l.onReplayEnd()
    }

    private fun onPhoneFix(
        lat: Double,
        lon: Double,
        accuracy: Float,
        latCas: Int,
        lonCas: Int,
    ) {
        if (released) return
        lastPhone = PhoneFix(lat, lon, accuracy)
        if (radio.isConnected) {
            radio.sendGps(latCas, lonCas)
            if (accuracy >= 0f) {
                publishStatus(
                    "GPS → radio (${"%.5f".format(lat)}, ${"%.5f".format(lon)})"
                )
            }
        } else if (accuracy >= 0f) {
            publishStatus("GPS OK — connectez la radio")
        }
        listener?.onPhoneFix(lat, lon, accuracy)
    }

    private fun onTarget(pos: K5ViewerProtocol.ReportedPosition) {
        if (released) return
        val atMs = System.currentTimeMillis()
        history.addLast(ReceivedTarget(pos, atMs))
        while (history.size > MAX_HISTORY) history.removeFirst()
        val line = "${timeFmt.format(Date(atMs))} · ${pos.label} · ${pos.sourceName} · " +
            "${"%.5f".format(pos.latDeg)}, ${"%.5f".format(pos.lonDeg)}" +
            if (pos.altM != 0) " · ${pos.altM} m" else ""
        SessionLog.addTarget(line)
        lastTargetSummary = "${pos.label} (${pos.sourceName})"
        publishStatus("Cible ${pos.label} (${pos.sourceName})")
        listener?.onReported(pos, atMs, false)
        updateNotification(force = false)
    }

    private fun onLinkChanged(connected: Boolean) {
        if (released) return
        if (connected) {
            usbSession = true
            acquireWake()
            phoneGps.lastFixCas()?.let { (latCas, lonCas) ->
                radio.sendGps(latCas, lonCas)
            }
        } else {
            releaseWake()
            usbSession = false
        }
        startAsForeground()
        updateNotification(force = true)
        listener?.onConnectionChanged(connected)
    }

    private fun publishStatus(msg: String) {
        if (released) return
        lastStatus = msg
        val spam = msg.startsWith("GPS → radio") || msg.startsWith("GPS OK")
        if (!spam && msg != lastLogged) {
            SessionLog.addStatus(msg)
            lastLogged = msg
        }
        listener?.onStatus(msg)
    }

    private fun loadLinkMode(): LinkMode {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(PREF_LINK_MODE, LinkMode.AUTO.name) ?: LinkMode.AUTO.name
        return runCatching { LinkMode.valueOf(raw) }.getOrDefault(LinkMode.AUTO)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun computeTypes(): Int {
        var types = 0
        if (hasLocationPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        // connectedDevice exists only from API 34. Older releases keep USB alive
        // for a started foreground service without that type bit.
        if (usbSession && Build.VERSION.SDK_INT >= 34) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }

    private fun startAsForeground() {
        if (released) return
        val types = computeTypes()
        try {
            if (Build.VERSION.SDK_INT >= 34 && types == 0) {
                if (foreground) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    foreground = false
                }
                return
            }
            if (Build.VERSION.SDK_INT >= 29 && types != 0) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            foreground = true
        } catch (e: Exception) {
            SessionLog.addStatus("Arrière-plan impossible: ${e.message}")
            shutdown()
        }
    }

    private fun notificationBody(): String {
        val target = lastTargetSummary
        return when {
            isConnected && target != null -> getString(R.string.notif_linked_target, target)
            isConnected -> getString(R.string.notif_linked)
            hasLocationPermission() -> getString(R.string.notif_gps_only)
            else -> getString(R.string.notif_waiting)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this,
            1,
            Intent(this, RadioLinkService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = notificationBody()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.quit), quit)
            .build()
    }

    private fun updateNotification(force: Boolean) {
        if (!foreground || released) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNotifAt < 1500L) return
        lastNotifAt = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun registerDetach() {
        if (detachRegistered) return
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(detachReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(detachReceiver, filter)
        }
        detachRegistered = true
    }

    private fun unregisterDetach() {
        if (!detachRegistered) return
        runCatching { unregisterReceiver(detachReceiver) }
        detachRegistered = false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    private fun releaseWake() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun shutdown() {
        if (released) return
        released = true
        listener = null
        releaseWake()
        if (::phoneGps.isInitialized) phoneGps.stop()
        if (::radio.isInitialized) radio.unregister()
        unregisterDetach()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.uvk1.map.action.START"
        const val ACTION_CONNECT = "com.uvk1.map.action.CONNECT"
        const val ACTION_QUIT = "com.uvk1.map.action.QUIT"

        private const val CHANNEL_ID = "uvk1_link"
        private const val NOTIFICATION_ID = 42
        private const val MAX_HISTORY = 2000
        private const val PREFS = "uvk1_map"
        private const val PREF_LINK_MODE = "link_mode"
    }
}
