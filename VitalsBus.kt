package com.example.watchvitals

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Shared live state between the background service and the screen. */
object VitalsBus {
    fun interface Listener {
        fun onChange()
    }

    data class Sample(val t: Long, val v: Int)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val hrList = ArrayList<Sample>()
    private val spList = ArrayList<Sample>()
    private val logLines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var status: String = "Not connected"
    @Volatile var deviceName: String = ""
    @Volatile var hint: String = ""
    @Volatile var hr: Int? = null
    @Volatile var spo2: Int? = null

    fun add(l: Listener) {
        listeners.add(l)
    }

    fun remove(l: Listener) {
        listeners.remove(l)
    }

    fun notifyChange() {
        main.post { for (l in listeners) l.onChange() }
    }

    @Synchronized
    fun addHr(v: Int) {
        hr = v
        hrList.add(Sample(System.currentTimeMillis(), v))
        if (hrList.size > 20000) hrList.removeAt(0)
    }

    @Synchronized
    fun addSpo2(v: Int) {
        spo2 = v
        spList.add(Sample(System.currentTimeMillis(), v))
        if (spList.size > 20000) spList.removeAt(0)
    }

    @Synchronized
    fun hrSamples(): List<Sample> = ArrayList(hrList)

    @Synchronized
    fun spSamples(): List<Sample> = ArrayList(spList)

    @Synchronized
    fun log(s: String) {
        logLines.add(fmt.format(Date()) + "  " + s)
        if (logLines.size > 300) logLines.removeAt(0)
    }

    @Synchronized
    fun logText(): String = logLines.joinToString("\n")

    @Synchronized
    fun clearSamples() {
        hrList.clear()
        spList.clear()
        hr = null
        spo2 = null
    }
}
