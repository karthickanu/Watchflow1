package com.example.watchvitals

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.UUID

/**
 * Foreground service that keeps a BLE connection to the watch alive, subscribes to the
 * standard Heart Rate (0x180D) and Pulse Oximeter (0x1822) services, and publishes readings.
 */
class VitalsService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.example.watchvitals.CONNECT"
        const val ACTION_STOP = "com.example.watchvitals.STOP"
        const val EXTRA_ADDR = "addr"
        private const val CH_LIVE = "live"
        private const val CH_ALERT = "alert"
        private const val BASE = "-0000-1000-8000-00805f9b34fb"

        private fun u16(v: Int): UUID = UUID.fromString(String.format("%08x%s", v, BASE))

        val HR_CHR: UUID = u16(0x2A37)
        val PLX_CONT: UUID = u16(0x2A5F)
        val PLX_SPOT: UUID = u16(0x2A5E)
        val CCCD: UUID = u16(0x2902)

        fun short(u: UUID): String {
            val s = u.toString()
            return if (s.startsWith("0000") && s.endsWith(BASE)) "0x" + s.substring(4, 8).uppercase() else s
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var want = false
    private var hrFromService = false
    private var lastAlert = 0L
    private var lastNotif = 0L
    private val queue = java.util.ArrayDeque<BluetoothGattCharacteristic>()
    private val lastRaw = HashMap<UUID, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Live monitoring", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Vitals alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("v", MODE_PRIVATE)
        when (intent?.action) {
            ACTION_STOP -> {
                want = false
                prefs.edit().putBoolean("want", false).apply()
                handler.removeCallbacksAndMessages(null)
                closeGatt()
                VitalsBus.hint = ""
                setStatus("Not connected")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                address = intent.getStringExtra(EXTRA_ADDR)
                prefs.edit().putString("addr", address).putBoolean("want", true).apply()
            }
            else -> {
                address = prefs.getString("addr", null)
                if (!prefs.getBoolean("want", false) || address == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        want = true
        startFg("Connecting...")
        connectNow(false)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        super.onDestroy()
    }

    // ---------- notifications ----------

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun liveNotif(text: String): Notification =
        Notification.Builder(this, CH_LIVE)
            .setContentTitle("Watch Vitals")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    private fun startFg(text: String) {
        val n = liveNotif(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, liveNotif(text))
    }

    private fun pref(key: String, def: Int): Int = getSharedPreferences("v", MODE_PRIVATE).getInt(key, def)

    private fun alert(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlert < 60000) return
        lastAlert = now
        val n = Notification.Builder(this, CH_ALERT)
            .setContentTitle("Vitals alert")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    // ---------- connection ----------

    private fun setStatus(s: String) {
        VitalsBus.status = s
        VitalsBus.notifyChange()
    }

    private fun retryLater() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (want) connectNow(true) }, 4000)
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        queue.clear()
        if (g != null) {
            try {
                g.disconnect()
            } catch (_: Exception) {
            }
            try {
                g.close()
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectNow(auto: Boolean) {
        val addr = address ?: return
        closeGatt()
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) {
                setStatus("Bluetooth is off, waiting...")
                retryLater()
                return
            }
            val dev = adapter.getRemoteDevice(addr)
            VitalsBus.deviceName = dev.name ?: addr
            setStatus(if (auto) "Reconnecting..." else "Connecting...")
            gatt = dev.connectGatt(this, auto, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            VitalsBus.log("Connect error: " + e.message)
            setStatus("Connect error, retrying...")
            retryLater()
        }
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                VitalsBus.log("Connected")
                handler.post {
                    if (g !== gatt) return@post
                    setStatus("Connected, discovering services...")
                    try {
                        g.discoverServices()
                    } catch (e: Exception) {
                        VitalsBus.log("discoverServices failed: " + e.message)
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                VitalsBus.log("Disconnected (status $status)")
                handler.post {
                    if (g !== gatt) {
                        try {
                            g.close()
                        } catch (_: Exception) {
                        }
                        return@post
                    }
                    closeGatt()
                    if (want) {
                        setStatus("Reconnecting...")
                        retryLater()
                    } else {
                        setStatus("Not connected")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    VitalsBus.log("Service discovery failed ($status)")
                    return@post
                }
                queue.clear()
                var gotHr = false
                var gotPlx = false
                for (s in g.services) {
                    VitalsBus.log("Service " + short(s.uuid))
                    for (c in s.characteristics) {
                        val p = c.properties
                        val names = ArrayList<String>()
                        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) names.add("read")
                        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ) names.add("write")
                        val canNotify = (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        val canIndicate = (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        if (canNotify) names.add("notify")
                        if (canIndicate) names.add("indicate")
                        VitalsBus.log("   " + short(c.uuid) + " [" + names.joinToString(", ") + "]")
                        if (c.uuid == HR_CHR) gotHr = true
                        if (c.uuid == PLX_CONT || c.uuid == PLX_SPOT) gotPlx = true
                        if (canNotify || canIndicate) queue.add(c)
                    }
                }
                hrFromService = gotHr
                when {
                    gotHr && gotPlx -> {
                        VitalsBus.hint = ""
                        setStatus("Live")
                    }
                    gotHr -> {
                        VitalsBus.hint = "Heart rate is streaming, but the watch does not offer the standard SpO2 service."
                        setStatus("Live (heart rate only)")
                    }
                    gotPlx -> {
                        VitalsBus.hint = ""
                        setStatus("Live (SpO2 only)")
                    }
                    else -> {
                        VitalsBus.hint = "Connected, but this watch does not expose the standard heart-rate or SpO2 " +
                            "Bluetooth services. Many watches use a private protocol that only their own app " +
                            "understands. The log below lists everything the watch offers."
                        setStatus("Connected, no standard vitals service")
                    }
                }
                nextSubscription()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { if (g === gatt) nextSubscription() }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            handle(c.uuid, v)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(c.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun nextSubscription() {
        val g = gatt ?: return
        val c = queue.pollFirst()
        if (c == null) {
            VitalsBus.log("Subscriptions ready")
            VitalsBus.notifyChange()
            return
        }
        try {
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD)
            if (d == null) {
                nextSubscription()
                return
            }
            val value = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                g.writeDescriptor(d)
            }
            if (!ok) {
                VitalsBus.log("Could not subscribe to " + short(c.uuid))
                nextSubscription()
            }
        } catch (e: Exception) {
            VitalsBus.log("Subscribe error: " + e.message)
            nextSubscription()
        }
    }

    // ---------- data ----------

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private fun sfloat(d: ByteArray, off: Int): Double? {
        val raw = u8(d[off]) or (u8(d[off + 1]) shl 8)
        if (raw == 0x07FF || raw == 0x0800 || raw == 0x07FE || raw == 0x0802 || raw == 0x0801) return null
        var m = raw and 0x0FFF
        var e = raw shr 12
        if (m >= 0x0800) m -= 0x1000
        if (e >= 0x08) e -= 0x10
        return m * Math.pow(10.0, e.toDouble())
    }

    private fun handle(u: UUID, d: ByteArray) {
        try {
            when (u) {
                HR_CHR -> {
                    if (d.size < 2) return
                    val flags = u8(d[0])
                    val v = if ((flags and 1) != 0 && d.size >= 3) u8(d[1]) or (u8(d[2]) shl 8) else u8(d[1])
                    onHr(v)
                }
                PLX_CONT, PLX_SPOT -> {
                    if (d.size < 3) return
                    val sp = sfloat(d, 1)
                    val pr = if (d.size >= 5) sfloat(d, 3) else null
                    if (sp != null) onSpo2(Math.round(sp).toInt())
                    if (pr != null && !hrFromService) onHr(Math.round(pr).toInt())
                }
                else -> {
                    val now = System.currentTimeMillis()
                    if (now - (lastRaw[u] ?: 0L) > 1000) {
                        lastRaw[u] = now
                        VitalsBus.log("DATA " + short(u) + ": " + d.joinToString(" ") { String.format("%02x", it) })
                        VitalsBus.notifyChange()
                    }
                }
            }
        } catch (e: Exception) {
            VitalsBus.log("Parse error: " + e.message)
        }
    }

    private fun onHr(v: Int) {
        if (v < 25 || v > 250) return
        VitalsBus.addHr(v)
        if (v < pref("hrLo", 50)) alert("Heart rate low: $v bpm")
        else if (v > pref("hrHi", 120)) alert("Heart rate high: $v bpm")
        afterSample()
    }

    private fun onSpo2(v: Int) {
        if (v < 50 || v > 100) return
        VitalsBus.addSpo2(v)
        if (v < pref("spLo", 92)) alert("SpO2 low: $v%")
        afterSample()
    }

    private fun afterSample() {
        VitalsBus.notifyChange()
        val now = System.currentTimeMillis()
        if (now - lastNotif > 3000) {
            lastNotif = now
            updateNotif("HR " + (VitalsBus.hr ?: "--") + " bpm  |  SpO2 " + (VitalsBus.spo2 ?: "--") + "%")
        }
    }
}
