package com.uvk1.map

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class UsbRadioLink(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onReported: (K5ViewerProtocol.ReportedPosition) -> Unit,
) : SerialInputOutputManager.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.uvk1.map.USB_PERMISSION"
        private const val KEEP_ALIVE_MS = 200L
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val parser = K5ViewerProtocol.Parser { pos ->
        mainHandler.post { onReported(pos) }
    }

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var keepAliveRunnable: Runnable? = null
    private var permissionReceiverRegistered = false

    val isConnected: Boolean get() = port?.isOpen == true

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                openDevice(device)
            } else {
                onStatus("Permission USB refusée")
            }
        }
    }

    fun register() {
        if (permissionReceiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
        permissionReceiverRegistered = true
    }

    fun unregister() {
        disconnect()
        if (permissionReceiverRegistered) {
            runCatching { context.unregisterReceiver(permissionReceiver) }
            permissionReceiverRegistered = false
        }
    }

    fun connect() {
        register()
        val device = findRadioDevice()
        if (device == null) {
            onStatus("Aucun UV-K1 détecté — câble OTG data ?")
            return
        }
        if (usbManager.hasPermission(device)) {
            openDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            onStatus("Autorisation USB…")
            usbManager.requestPermission(device, pi)
        }
    }

    fun disconnect() {
        stopKeepAlive()
        ioManager?.listener = null
        runCatching { ioManager?.stop() }
        ioManager = null
        runCatching { port?.close() }
        port = null
        onStatus("Radio déconnectée")
    }

    fun sendGps(latCas: Int, lonCas: Int) {
        write(K5ViewerProtocol.makeGpsPacket(latCas, lonCas))
    }

    private fun findRadioDevice(): UsbDevice? {
        val preferred = usbManager.deviceList.values.firstOrNull {
            it.vendorId == K5ViewerProtocol.VENDOR_ID &&
                (it.productId == K5ViewerProtocol.PRODUCT_ID || it.productId == 0xFFFF)
        }
        if (preferred != null) return preferred
        // Any device claimed by our CDC prober
        val drivers = customProber().findAllDrivers(usbManager)
        return drivers.firstOrNull()?.device
            ?: usbManager.deviceList.values.firstOrNull()
    }

    private fun customProber(): UsbSerialProber {
        val table = ProbeTable().apply {
            addProduct(
                K5ViewerProtocol.VENDOR_ID,
                K5ViewerProtocol.PRODUCT_ID,
                CdcAcmSerialDriver::class.java
            )
        }
        return UsbSerialProber(table)
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val drivers = customProber().findAllDrivers(usbManager)
            val driver = drivers.firstOrNull { it.device.deviceId == device.deviceId }
                ?: UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                    .firstOrNull { it.device.deviceId == device.deviceId }
            if (driver == null || driver.ports.isEmpty()) {
                onStatus("Pilote CDC introuvable")
                return
            }
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                onStatus("Impossible d'ouvrir le périphérique USB")
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
            port = serialPort
            ioManager = SerialInputOutputManager(serialPort, this).also {
                ioExecutor.submit(it)
            }
            startKeepAlive()
            write(K5ViewerProtocol.KEEP_ALIVE)
            onStatus("Radio connectée (${device.vendorId.toString(16)}:${device.productId.toString(16)})")
        } catch (e: Exception) {
            disconnect()
            onStatus("Erreur USB: ${e.message}")
        }
    }

    private fun write(bytes: ByteArray) {
        val p = port ?: return
        ioExecutor.execute {
            runCatching { p.write(bytes, 1000) }
        }
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        val r = object : Runnable {
            override fun run() {
                if (isConnected) {
                    write(K5ViewerProtocol.KEEP_ALIVE)
                    mainHandler.postDelayed(this, KEEP_ALIVE_MS)
                }
            }
        }
        keepAliveRunnable = r
        mainHandler.postDelayed(r, KEEP_ALIVE_MS)
    }

    private fun stopKeepAlive() {
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
    }

    override fun onNewData(data: ByteArray) {
        parser.push(data)
    }

    override fun onRunError(e: Exception) {
        mainHandler.post {
            onStatus("Lien USB perdu: ${e.message}")
            disconnect()
        }
    }
}
