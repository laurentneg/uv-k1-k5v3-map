package com.uvk1.map

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class UsbRadioLink(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onReported: (K5ViewerProtocol.ReportedPosition) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {},
    private val onChooseDevice: ((candidates: List<UsbSerialDriver>, pick: (UsbSerialDriver) -> Unit) -> Unit)? = null,
) : SerialInputOutputManager.Listener {

    companion object {
        const val ACTION_USB_PERMISSION = "com.uvk1.map.USB_PERMISSION"
        private const val EXTRA_TOKEN = "com.uvk1.map.extra.USB_TOKEN"
        private const val REQUEST_USB_PERMISSION = 34
        private const val KEEP_ALIVE_MS = 200L

        // QinHeng
        private const val VID_QINHENG = 0x1A86
        // Silicon Labs
        private const val VID_SILABS = 0x10C4
        // FTDI
        private const val VID_FTDI = 0x0403
        // Prolific
        private const val VID_PROLIFIC = 0x067B

        @Volatile
        private var permissionHandler: ((Intent) -> Unit)? = null

        @Volatile
        private var expectedToken: String? = null

        fun dispatchPermission(intent: Intent) {
            val action = intent.action
            if (action != null && action != ACTION_USB_PERMISSION) return
            val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
            if (token != expectedToken) return
            expectedToken = null
            permissionHandler?.invoke(intent)
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    var linkMode: LinkMode = LinkMode.AUTO

    private val rxBytes = AtomicInteger(0)
    private val txBytes = AtomicInteger(0)
    private val reportedCount = AtomicInteger(0)
    private val posFrames = AtomicInteger(0)
    private val posBad = AtomicInteger(0)
    private val parser = K5ViewerProtocol.Parser(
        onReported = { pos ->
            reportedCount.incrementAndGet()
            mainHandler.post {
                onStatus(
                    "RX ${rxBytes.get()} B · TX ${txBytes.get()} B · " +
                        "${reportedCount.get()} cible(s) · ${pos.label}"
                )
                onReported(pos)
            }
        },
        onPosFrame = { ok ->
            posFrames.incrementAndGet()
            if (!ok) posBad.incrementAndGet()
            if (!ok) {
                mainHandler.post {
                    onStatus("Trame 0x08 reçue mais XOR invalide (${posBad.get()})")
                }
            }
        },
    )

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var keepAliveRunnable: Runnable? = null
    private var statusRunnable: Runnable? = null
    private var pendingDriver: UsbSerialDriver? = null

    val isConnected: Boolean get() = port?.isOpen == true

    fun register() {
        permissionHandler = ::handlePermission
    }

    fun unregister() {
        if (permissionHandler != null) permissionHandler = null
        expectedToken = null
        disconnect()
    }

    fun connect() {
        register()
        if (isConnected) {
            onStatus("Radio déjà liée")
            notifyConnection(true)
            return
        }
        val candidates = candidatesForMode(linkMode)
        if (candidates.isEmpty()) {
            onStatus(
                when (linkMode) {
                    LinkMode.USB_C -> "Aucun UV-K1 USB-C (36B7) — OTG data ?"
                    LinkMode.CABLE_K -> "Aucun câble K (CH340/CP210x/FTDI/PL2303)"
                    LinkMode.AUTO -> "Aucun adaptateur série — USB-C ou câble K ?"
                }
            )
            notifyConnection(false)
            return
        }
        if (candidates.size == 1 || onChooseDevice == null) {
            requestOpen(candidates.first())
        } else {
            onStatus("${candidates.size} périphériques — choisissez")
            onChooseDevice.invoke(candidates) { requestOpen(it) }
        }
    }

    fun disconnect() {
        stopKeepAlive()
        stopStatusTicker()
        val mgr = ioManager
        ioManager = null
        mgr?.listener = null
        runCatching { mgr?.stop() }
        val p = port
        port = null
        runCatching {
            p?.setDTR(false)
            p?.setRTS(false)
            p?.close()
        }
        pendingDriver = null
        onStatus("Radio déconnectée")
        notifyConnection(false)
    }

    fun sendGps(latCas: Int, lonCas: Int) {
        write(K5ViewerProtocol.makeGpsPacket(latCas, lonCas))
    }

    private fun handlePermission(intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val driver = pendingDriver
        pendingDriver = null
        if (granted && device != null && driver != null &&
            driver.device.deviceId == device.deviceId
        ) {
            openDriver(driver)
        } else if (granted && device != null) {
            val found = allDrivers().firstOrNull { it.device.deviceId == device.deviceId }
            if (found != null) openDriver(found) else {
                onStatus("Pilote introuvable après permission")
                notifyConnection(false)
            }
        } else {
            onStatus("Permission USB refusée")
            notifyConnection(false)
        }
    }

    private fun requestOpen(driver: UsbSerialDriver) {
        val device = driver.device
        onStatus(
            "USB ${device.vendorId.toString(16)}:${device.productId.toString(16)} · " +
                chipName(device) + " · ${linkMode.label}"
        )
        if (usbManager.hasPermission(device)) {
            openDriver(driver)
        } else {
            pendingDriver = driver
            val pi = try {
                permissionPendingIntent()
            } catch (e: IllegalArgumentException) {
                pendingDriver = null
                onStatus("Permission USB impossible: ${e.message}")
                notifyConnection(false)
                return
            }
            onStatus("Autorisation USB…")
            usbManager.requestPermission(device, pi)
        }
    }

    /**
     * Android 14+ (targetSdk 34) rejects a mutable PendingIntent whose Intent is
     * implicit. UsbManager must be able to add the grant extras, so the intent
     * stays mutable but is aimed at [UsbPermissionReceiver].
     */
    private fun permissionPendingIntent(): PendingIntent {
        val token = UUID.randomUUID().toString()
        expectedToken = token
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
            component = ComponentName(context, UsbPermissionReceiver::class.java)
            putExtra(EXTRA_TOKEN, token)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQUEST_USB_PERMISSION, intent, flags)
    }

    private fun notifyConnection(connected: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onConnectionChanged(connected)
        } else {
            mainHandler.post { onConnectionChanged(connected) }
        }
    }

    private fun allDrivers(): List<UsbSerialDriver> {
        val merged = LinkedHashMap<Int, UsbSerialDriver>()
        for (d in uartProber().findAllDrivers(usbManager)) {
            merged[d.device.deviceId] = d
        }
        for (d in UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) {
            merged.putIfAbsent(d.device.deviceId, d)
        }
        return merged.values.toList()
    }

    private fun candidatesForMode(mode: LinkMode): List<UsbSerialDriver> {
        val all = allDrivers()
        return when (mode) {
            LinkMode.USB_C -> all.filter { isFusionCdc(it.device) }
            LinkMode.CABLE_K -> all.filter { isUartAdapter(it.device) }
            LinkMode.AUTO -> {
                val cdc = all.filter { isFusionCdc(it.device) }
                if (cdc.isNotEmpty()) cdc else all.filter { isUartAdapter(it.device) }
            }
        }
    }

    private fun isFusionCdc(device: UsbDevice): Boolean =
        device.vendorId == K5ViewerProtocol.VENDOR_ID &&
            (device.productId == K5ViewerProtocol.PRODUCT_ID || device.productId == 0xFFFF)

    private fun isUartAdapter(device: UsbDevice): Boolean {
        if (isFusionCdc(device)) return false
        return device.vendorId == VID_QINHENG ||
            device.vendorId == VID_SILABS ||
            device.vendorId == VID_FTDI ||
            device.vendorId == VID_PROLIFIC
    }

    private fun chipName(device: UsbDevice): String = when (device.vendorId) {
        K5ViewerProtocol.VENDOR_ID -> "CDC K1"
        VID_QINHENG -> "CH340/CH91x"
        VID_SILABS -> "CP210x"
        VID_FTDI -> "FTDI"
        VID_PROLIFIC -> "PL2303"
        else -> "UART"
    }

    private fun uartProber(): UsbSerialProber {
        val table = ProbeTable().apply {
            // Radio USB-C CDC
            addProduct(
                K5ViewerProtocol.VENDOR_ID,
                K5ViewerProtocol.PRODUCT_ID,
                CdcAcmSerialDriver::class.java
            )
            addProduct(K5ViewerProtocol.VENDOR_ID, 0xFFFF, CdcAcmSerialDriver::class.java)

            // CH340 / CH341
            addProduct(VID_QINHENG, 0x7523, Ch34xSerialDriver::class.java)
            addProduct(VID_QINHENG, 0x5523, Ch34xSerialDriver::class.java)
            addProduct(VID_QINHENG, 0x55D4, Ch34xSerialDriver::class.java) // CH9102
            addProduct(VID_QINHENG, 0xEABB, Ch34xSerialDriver::class.java)

            // CP210x (common PIDs)
            addProduct(VID_SILABS, 0xEA60, Cp21xxSerialDriver::class.java)
            addProduct(VID_SILABS, 0xEA61, Cp21xxSerialDriver::class.java)
            addProduct(VID_SILABS, 0xEA70, Cp21xxSerialDriver::class.java)
            addProduct(VID_SILABS, 0xEA71, Cp21xxSerialDriver::class.java)

            // FTDI
            addProduct(VID_FTDI, 0x6001, FtdiSerialDriver::class.java)
            addProduct(VID_FTDI, 0x6010, FtdiSerialDriver::class.java)
            addProduct(VID_FTDI, 0x6011, FtdiSerialDriver::class.java)
            addProduct(VID_FTDI, 0x6014, FtdiSerialDriver::class.java)
            addProduct(VID_FTDI, 0x6015, FtdiSerialDriver::class.java)

            // Prolific
            addProduct(VID_PROLIFIC, 0x2303, ProlificSerialDriver::class.java)
            addProduct(VID_PROLIFIC, 0x23A3, ProlificSerialDriver::class.java)
            addProduct(VID_PROLIFIC, 0x23B3, ProlificSerialDriver::class.java)
            addProduct(VID_PROLIFIC, 0x23C3, ProlificSerialDriver::class.java)
        }
        return UsbSerialProber(table)
    }

    private fun openDriver(driver: UsbSerialDriver) {
        try {
            disconnectQuiet()
            if (driver.ports.isEmpty()) {
                onStatus("Aucun port série sur ${chipName(driver.device)}")
                notifyConnection(false)
                return
            }
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                onStatus("Impossible d'ouvrir le périphérique USB")
                notifyConnection(false)
                return
            }
            val serialPort = driver.ports[0]
            serialPort.open(connection)
            serialPort.setParameters(
                K5ViewerProtocol.BAUD,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            // CDC needs DTR for radio TX; UART adapters: harmless / often required by chip.
            runCatching {
                serialPort.setDTR(true)
                serialPort.setRTS(true)
            }
            runCatching { serialPort.purgeHwBuffers(true, true) }

            port = serialPort
            rxBytes.set(0)
            txBytes.set(0)
            reportedCount.set(0)
            posFrames.set(0)
            posBad.set(0)

            val mgr = SerialInputOutputManager(serialPort, this)
            mgr.setReadTimeout(50)
            mgr.setWriteTimeout(500)
            ioManager = mgr
            mgr.start()

            startKeepAlive()
            startStatusTicker()
            write(K5ViewerProtocol.KEEP_ALIVE)
            write(K5ViewerProtocol.KEEP_ALIVE)
            val kind = if (isFusionCdc(driver.device)) "USB-C" else "câble K"
            onStatus(
                "Lié $kind ${driver.device.vendorId.toString(16)}:" +
                    "${driver.device.productId.toString(16)} · attente RX…"
            )
            notifyConnection(true)
        } catch (e: Exception) {
            disconnect()
            onStatus("Erreur USB: ${e.message}")
        }
    }

    /** Disconnect without status spam (used before reopen). */
    private fun disconnectQuiet() {
        stopKeepAlive()
        stopStatusTicker()
        val mgr = ioManager
        ioManager = null
        mgr?.listener = null
        runCatching { mgr?.stop() }
        val p = port
        port = null
        runCatching {
            p?.setDTR(false)
            p?.setRTS(false)
            p?.close()
        }
    }

    private fun write(bytes: ByteArray) {
        val mgr = ioManager
        if (mgr == null) {
            onStatus("TX ignorée (pas de lien)")
            return
        }
        try {
            mgr.writeAsync(bytes)
            txBytes.addAndGet(bytes.size)
        } catch (e: Exception) {
            onStatus("TX échouée: ${e.message}")
        }
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        val r = object : Runnable {
            override fun run() {
                if (isConnected) {
                    runCatching {
                        port?.setDTR(true)
                        port?.setRTS(true)
                    }
                    write(K5ViewerProtocol.KEEP_ALIVE)
                    mainHandler.postDelayed(this, KEEP_ALIVE_MS)
                }
            }
        }
        keepAliveRunnable = r
        mainHandler.post(r)
    }

    private fun stopKeepAlive() {
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
    }

    private fun startStatusTicker() {
        stopStatusTicker()
        val r = object : Runnable {
            override fun run() {
                if (!isConnected) return
                if (reportedCount.get() == 0) {
                    val rx = rxBytes.get()
                    val tx = txBytes.get()
                    val frames = posFrames.get()
                    onStatus(
                        "TX $tx · RX $rx · 0x08=$frames · " +
                            when {
                                tx < 16 -> "TX morte"
                                rx == 0 -> "pas de RX radio"
                                frames == 0 -> "RX OK, aucune position"
                                else -> "pos reçues, parse?"
                            }
                    )
                }
                mainHandler.postDelayed(this, 2000L)
            }
        }
        statusRunnable = r
        mainHandler.postDelayed(r, 2000L)
    }

    private fun stopStatusTicker() {
        statusRunnable?.let { mainHandler.removeCallbacks(it) }
        statusRunnable = null
    }

    override fun onNewData(data: ByteArray) {
        rxBytes.addAndGet(data.size)
        parser.push(data)
    }

    override fun onRunError(e: Exception) {
        mainHandler.post {
            onStatus("Lien USB perdu: ${e.message}")
            disconnect()
        }
    }
}

/** Explicit target for the USB grant. Required so the PendingIntent is not implicit. */
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UsbRadioLink.dispatchPermission(intent)
    }
}
