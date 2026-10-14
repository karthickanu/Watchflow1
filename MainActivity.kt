package com.example.watchvitals

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private data class Found(val name: String, val address: String, val rssi: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()

    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var hrView: TextView
    private lateinit var spView: TextView
    private lateinit var hrStats: TextView
    private lateinit var spStats: TextView
    private lateinit var hrChart: SparkView
    private lateinit var spChart: SparkView
    private lateinit var logView: TextView

    private val listener = VitalsBus.Listener { refresh() }

    private val cBg = Color.parseColor("#0E1015")
    private val cCard = Color.parseColor("#171A22")
    private val cText = Color.parseColor("#EEF0F5")
    private val cMuted = Color.parseColor("#98A0B0")
    private val cHr = Color.parseColor("#FF5C7C")
    private val cSp = Color.parseColor("#4AA8FF")
    private val cOk = Color.parseColor("#38D17A")
    private val cWarn = Color.parseColor("#F0A93A")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun tv(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(32))
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(cBg)
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)

        root.addView(tv("Watch Vitals", 24f, cText, true))
        statusView = tv("Not connected", 14f, cWarn)
        root.addView(statusView)
        hintView = tv("", 13f, cWarn)
        hintView.setPadding(0, dp(8), 0, 0)
        hintView.visibility = View.GONE
        root.addView(hintView)

        // metric cards
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val c1 = LinearLayout(this)
        c1.orientation = LinearLayout.VERTICAL
        c1.background = roundBg(cCard, 16)
        c1.setPadding(dp(14), dp(12), dp(14), dp(12))
        c1.addView(tv("Heart rate (bpm)", 12f, cMuted))
        hrView = tv("--", 52f, cHr, true)
        c1.addView(hrView)
        hrStats = tv("min -- avg -- max --", 11f, cMuted)
        c1.addView(hrStats)

        val c2 = LinearLayout(this)
        c2.orientation = LinearLayout.VERTICAL
        c2.background = roundBg(cCard, 16)
        c2.setPadding(dp(14), dp(12), dp(14), dp(12))
        c2.addView(tv("Blood oxygen (%)", 12f, cMuted))
        spView = tv("--", 52f, cSp, true)
        c2.addView(spView)
        spStats = tv("min -- avg -- max --", 11f, cMuted)
        c2.addView(spStats)

        val lp1 = LinearLayout.LayoutParams(0, wrap, 1f)
        lp1.rightMargin = dp(6)
        val lp2 = LinearLayout.LayoutParams(0, wrap, 1f)
        lp2.leftMargin = dp(6)
        row.addView(c1, lp1)
        row.addView(c2, lp2)
        val rowLp = LinearLayout.LayoutParams(match, wrap)
        rowLp.topMargin = dp(16)
        root.addView(row, rowLp)

        // charts
        hrChart = SparkView(this)
        hrChart.lineColor = cHr
        hrChart.minV = 50f
        hrChart.maxV = 110f
        hrChart.label = "Heart rate, last 5 min"
        val ch1 = LinearLayout.LayoutParams(match, dp(120))
        ch1.topMargin = dp(14)
        root.addView(hrChart, ch1)

        spChart = SparkView(this)
        spChart.lineColor = cSp
        spChart.minV = 90f
        spChart.maxV = 100f
        spChart.label = "SpO2, last 5 min"
        val ch2 = LinearLayout.LayoutParams(match, dp(120))
        ch2.topMargin = dp(10)
        root.addView(spChart, ch2)

        // buttons
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        val scan = Button(this)
        scan.text = "Scan and connect"
        scan.setOnClickListener { onScanClicked() }
        val disc = Button(this)
        disc.text = "Disconnect"
        disc.setOnClickListener {
            startService(Intent(this, VitalsService::class.java).setAction(VitalsService.ACTION_STOP))
        }
        btnRow.addView(scan, LinearLayout.LayoutParams(0, wrap, 1f))
        btnRow.addView(disc, LinearLayout.LayoutParams(0, wrap, 1f))
        val btnLp = LinearLayout.LayoutParams(match, wrap)
        btnLp.topMargin = dp(14)
        root.addView(btnRow, btnLp)

        // alert thresholds
        val prefs = getSharedPreferences("v", MODE_PRIVATE)
        val alertTitle = tv("Alert thresholds", 14f, cText, true)
        val atLp = LinearLayout.LayoutParams(match, wrap)
        atLp.topMargin = dp(16)
        root.addView(alertTitle, atLp)

        fun field(label: String, key: String, def: Int): LinearLayout {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.addView(tv(label, 11f, cMuted))
            val et = EditText(this)
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setTextColor(cText)
            et.setSingleLine()
            et.setText(prefs.getInt(key, def).toString())
            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val n = s?.toString()?.toIntOrNull()
                    if (n != null) prefs.edit().putInt(key, n).apply()
                }

                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
            box.addView(et)
            return box
        }

        val alertRow = LinearLayout(this)
        alertRow.orientation = LinearLayout.HORIZONTAL
        alertRow.addView(field("HR low", "hrLo", 50), LinearLayout.LayoutParams(0, wrap, 1f))
        alertRow.addView(field("HR high", "hrHi", 120), LinearLayout.LayoutParams(0, wrap, 1f))
        alertRow.addView(field("SpO2 low", "spLo", 92), LinearLayout.LayoutParams(0, wrap, 1f))
        root.addView(alertRow, LinearLayout.LayoutParams(match, wrap))

        // session tools
        val toolRow = LinearLayout(this)
        toolRow.orientation = LinearLayout.HORIZONTAL
        val share = Button(this)
        share.text = "Share CSV"
        share.setOnClickListener { shareCsv() }
        val clear = Button(this)
        clear.text = "Clear readings"
        clear.setOnClickListener {
            VitalsBus.clearSamples()
            refresh()
        }
        toolRow.addView(share, LinearLayout.LayoutParams(0, wrap, 1f))
        toolRow.addView(clear, LinearLayout.LayoutParams(0, wrap, 1f))
        root.addView(toolRow, LinearLayout.LayoutParams(match, wrap))

        // log
        val logTitle = tv("Connection log and watch services", 14f, cText, true)
        val ltLp = LinearLayout.LayoutParams(match, wrap)
        ltLp.topMargin = dp(16)
        root.addView(logTitle, ltLp)
        logView = tv("", 11f, cMuted)
        logView.typeface = Typeface.MONOSPACE
        root.addView(logView)

        val foot = tv(
            "Not a medical device. Readings from consumer watches can be inaccurate; do not use them " +
                "for diagnosis or treatment decisions. If you feel unwell, get medical help.",
            11f, cMuted
        )
        foot.setPadding(0, dp(18), 0, 0)
        root.addView(foot)
    }

    override fun onStart() {
        super.onStart()
        VitalsBus.add(listener)
        refresh()
    }

    override fun onStop() {
        VitalsBus.remove(listener)
        super.onStop()
    }

    private fun stats(a: List<VitalsBus.Sample>): String {
        if (a.isEmpty()) return "min -- avg -- max --"
        var mn = Int.MAX_VALUE
        var mx = Int.MIN_VALUE
        var sum = 0L
        for (s in a) {
            if (s.v < mn) mn = s.v
            if (s.v > mx) mx = s.v
            sum += s.v
        }
        return "min $mn   avg ${sum / a.size}   max $mx"
    }

    private fun refresh() {
        val st = VitalsBus.status
        val dev = VitalsBus.deviceName
        statusView.text = if (dev.isNotEmpty()) "$st  |  $dev" else st
        statusView.setTextColor(if (st.startsWith("Live")) cOk else cWarn)
        val hint = VitalsBus.hint
        hintView.text = hint
        hintView.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
        hrView.text = VitalsBus.hr?.toString() ?: "--"
        spView.text = VitalsBus.spo2?.toString() ?: "--"
        val h = VitalsBus.hrSamples()
        val s = VitalsBus.spSamples()
        hrStats.text = stats(h)
        spStats.text = stats(s)
        hrChart.data = h
        hrChart.invalidate()
        spChart.data = s
        spChart.invalidate()
        logView.text = VitalsBus.logText()
    }

    // ---------- permissions and scanning ----------

    private fun essential(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun wanted(): List<String> {
        val l = ArrayList(essential())
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun onScanClicked() {
        val missing = wanted().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        } else {
            startScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        val ok = essential().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (ok) startScan() else Toast.makeText(this, "Bluetooth permission is needed to find your watch", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "Bluetooth scanner unavailable", Toast.LENGTH_LONG).show()
            return
        }
        found.clear()
        VitalsBus.status = "Scanning for 8 seconds..."
        refresh()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = d.name ?: result.scanRecord?.deviceName ?: "Unknown"
                found[d.address] = Found(name, d.address, result.rssi)
            }
        }
        try {
            scanner.startScan(cb)
        } catch (e: Exception) {
            Toast.makeText(this, "Scan failed: " + e.message, Toast.LENGTH_LONG).show()
            return
        }
        handler.postDelayed({
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
            VitalsBus.status = "Not connected"
            refresh()
            showPicker()
        }, 8000)
    }

    private fun showPicker() {
        val list = found.values.sortedWith(
            compareByDescending<Found> {
                val n = it.name.lowercase()
                n.contains("noise") || n.contains("pulse") || n.contains("colorfit")
            }.thenByDescending { it.name != "Unknown" }.thenByDescending { it.rssi }
        )
        if (list.isEmpty()) {
            Toast.makeText(this, "No devices found. Is the watch awake and Bluetooth on? (On Android 11 or older, location must be on too.)", Toast.LENGTH_LONG).show()
            return
        }
        val labels = Array<CharSequence>(list.size) { i ->
            list[i].name + "\n" + list[i].address + "  (" + list[i].rssi + " dBm)"
        }
        AlertDialog.Builder(this)
            .setTitle("Choose your watch")
            .setItems(labels) { _, i -> connectTo(list[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectTo(f: Found) {
        VitalsBus.log("Selected " + f.name + " " + f.address)
        VitalsBus.hint = ""
        val i = Intent(this, VitalsService::class.java)
            .setAction(VitalsService.ACTION_CONNECT)
            .putExtra(VitalsService.EXTRA_ADDR, f.address)
        startForegroundService(i)
    }

    private fun shareCsv() {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val sb = StringBuilder("timestamp,metric,value\n")
        for (s in VitalsBus.hrSamples().takeLast(5000)) {
            sb.append(fmt.format(Date(s.t))).append(",heart_rate_bpm,").append(s.v).append('\n')
        }
        for (s in VitalsBus.spSamples().takeLast(5000)) {
            sb.append(fmt.format(Date(s.t))).append(",spo2_percent,").append(s.v).append('\n')
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, sb.toString())
        startActivity(Intent.createChooser(send, "Share readings"))
    }
}

/** Minimal line chart for the last five minutes of readings. */
class SparkView(ctx: Context) : View(ctx) {
    var data: List<VitalsBus.Sample> = emptyList()
    var lineColor: Int = Color.WHITE
    var minV: Float = 0f
    var maxV: Float = 100f
    var label: String = ""

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 28f
    }

    init {
        val g = GradientDrawable()
        g.setColor(Color.parseColor("#171A22"))
        g.cornerRadius = 40f
        background = g
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val windowMs = 300000L
        val t0 = System.currentTimeMillis() - windowMs
        val pts = data.filter { it.t >= t0 }
        var lo = minV
        var hi = maxV
        if (pts.isNotEmpty()) {
            var mn = Int.MAX_VALUE
            var mx = Int.MIN_VALUE
            for (p in pts) {
                if (p.v < mn) mn = p.v
                if (p.v > mx) mx = p.v
            }
            lo = minOf(lo, mn - 3f)
            hi = maxOf(hi, mx + 3f)
        }
        val padT = 44f
        val padB = 16f
        val padX = 20f
        val span = hi - lo
        for (i in 0..2) {
            val frac = i / 2f
            val y = padT + (1f - frac) * (h - padT - padB)
            canvas.drawLine(padX, y, w - padX, y, gridPaint)
            canvas.drawText(Math.round(lo + span * frac).toString(), w - padX - 70f, y - 6f, textPaint)
        }
        canvas.drawText(label, padX, 30f, textPaint)
        if (pts.size >= 2) {
            val path = Path()
            for ((i, p) in pts.withIndex()) {
                val x = padX + (p.t - t0).toFloat() / windowMs * (w - 2 * padX)
                val y = padT + (1f - (p.v - lo) / span) * (h - padT - padB)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            linePaint.color = lineColor
            canvas.drawPath(path, linePaint)
        }
    }
}
