package com.jeiel85.motionpointer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.pow

/**
 * 폰을 공중 포인터로 쓴다. 가운데 패드를 누르고 있는 동안 폰을 기울이면 커서가 움직인다.
 * 연결 방식은 두 가지: 블루투스 HID 마우스(PC 설치 불필요) / PC 앱(motion-pointer).
 */
@SuppressLint("ClickableViewAccessibility", "MissingPermission")
class MainActivity : Activity() {
    private lateinit var content: View
    private lateinit var statusBadge: TextView
    private lateinit var statusDot: View
    private lateinit var statusDetail: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var refreshButton: ImageButton
    private lateinit var sensitivityLabel: TextView

    private val prefs by lazy { getSharedPreferences("motion-pointer", MODE_PRIVATE) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }

    private val pointerThread = HandlerThread("pointer").apply { start() }
    private val pointer = Handler(pointerThread.looper)

    // 아래 상태는 pointer 스레드에서만 읽고 쓴다.
    private val mapper = PointerMapper()
    private var output: PointerOutput? = null
    private var buttons = 0
    private var moving = false

    // UI 스레드 상태
    private var linkActive = false // 연결 시도부터 끊기까지
    private var connected = false
    private var padHeld = false
    private var sensorRegistered = false
    private var devices: List<BluetoothDevice> = emptyList()
    private var sensorMissing = false

    private val sensorListener = object : SensorEventListener {
        private val q = FloatArray(4)

        override fun onSensorChanged(event: SensorEvent) {
            if (!moving) return
            SensorManager.getQuaternionFromVector(q, event.values)
            val delta = mapper.update(
                Quat(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()),
                event.timestamp,
            )
            if (delta.dx != 0 || delta.dy != 0) output?.send(delta.dx, delta.dy, buttons, 0)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        statusBadge = findViewById(R.id.statusBadge)
        statusDot = findViewById(R.id.statusDot)
        statusDetail = findViewById(R.id.statusDetail)
        modeGroup = findViewById(R.id.mode)
        deviceSpinner = findViewById(R.id.device)
        connectButton = findViewById(R.id.connect)
        refreshButton = findViewById(R.id.refresh)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)

        setUpEdgeToEdge()

        modeGroup.check(if (prefs.getString(PREF_MODE, MODE_HID) == MODE_PC) R.id.modePc else R.id.modeHid)
        modeGroup.setOnCheckedChangeListener { _, _ -> onModeChanged() }
        connectButton.setOnClickListener { if (linkActive) disconnect("연결을 끊었습니다.") else connect() }
        refreshButton.setOnClickListener { loadDevices() }
        setUpSensitivity()
        setUpPad()
        setUpButton(R.id.leftButton, Buttons.LEFT)
        setUpButton(R.id.rightButton, Buttons.RIGHT)
        setUpScrollStrip()

        if (sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null) {
            sensorMissing = true
            connectButton.isEnabled = false
            refreshButton.isEnabled = false
            showStatus(Status.ERROR, "회전 센서 없음", "이 폰에는 자이로 기반 회전 센서가 없어 쓸 수 없습니다.")
            return
        }

        // 기기 목록은 onResume에서 읽는다(블루투스를 나중에 켜거나 새로 페어링해도 반영되도록).
        if (!hasBluetoothPermission()) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
    }

    /**
     * 화면을 상태 바·내비게이션 바 아래까지 쓰고, 그 높이만큼 내용을 밀어 넣는다.
     * API 35부터는 시스템이 강제로 이렇게 배치하므로, 모든 버전에서 같은 방식으로 처리한다.
     */
    private fun setUpEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        val baseStart = content.paddingStart
        val baseTop = content.paddingTop
        val baseEnd = content.paddingEnd
        val baseBottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            view.setPadding(
                baseStart + bars.left,
                baseTop + bars.top,
                baseEnd + bars.right,
                baseBottom + bars.bottom,
            )
            insets
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (hasBluetoothPermission()) {
            loadDevices()
        } else {
            showStatus(
                Status.ERROR, "권한 필요",
                "'근처 기기' 권한이 있어야 블루투스로 연결할 수 있습니다. 설정 > 앱 > Motion Pointer에서 허용해 주세요.")
        }
    }

    override fun onResume() {
        super.onResume()
        // 연결 버튼 상태로 막지 않는다. 페어링된 기기가 없으면 버튼이 꺼져 있는데,
        // 그 상태로 블루투스 설정에 다녀오면 목록을 다시 읽어야 한다.
        if (!linkActive && !sensorMissing && hasBluetoothPermission()) loadDevices()
    }

    override fun onPause() {
        super.onPause()
        unregisterSensor()
        // 화면을 벗어나면 터치가 취소되므로 패드를 누른 상태를 버린다.
        // 그대로 두면 돌아왔을 때 손가락 없이 센서만 켜진다.
        padHeld = false
        findViewById<View>(R.id.pad).isPressed = false
        pointer.post {
            moving = false
            if (buttons != 0) {
                buttons = 0
                output?.send(0, 0, 0, 0)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensor()
        pointer.post {
            output?.close()
            output = null
        }
        pointerThread.quitSafely()
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun onModeChanged() {
        val pcMode = modeGroup.checkedRadioButtonId == R.id.modePc
        prefs.edit().putString(PREF_MODE, if (pcMode) MODE_PC else MODE_HID).apply()
        if (!linkActive && !sensorMissing) loadDevices()
    }

    private fun loadDevices() {
        if (sensorMissing) return
        val bt = adapter
        // 다시 읽을 때 사용자가 고르던 기기를 유지한다. 처음이면 마지막으로 연결한 기기.
        val selected = devices.getOrNull(deviceSpinner.selectedItemPosition)?.address
            ?: prefs.getString(PREF_DEVICE, null)

        if (bt == null || !bt.isEnabled) {
            setDeviceLabels(emptyList(), "블루투스가 꺼져 있음")
            showStatus(Status.IDLE, null, "블루투스가 꺼져 있습니다. 블루투스를 켜고 이 화면으로 돌아오세요.")
            return
        }

        // 컴퓨터를 앞에, 그다음 이름순.
        devices = bt.bondedDevices.sortedWith(
            compareBy<BluetoothDevice>(
                { it.bluetoothClass?.majorDeviceClass != BluetoothClass.Device.Major.COMPUTER },
                { it.label() },
            ))

        if (devices.isEmpty()) {
            setDeviceLabels(emptyList(), "페어링된 기기 없음")
            showStatus(Status.IDLE, null, "페어링된 기기가 없습니다. 폰 블루투스 설정에서 PC와 페어링한 뒤 이 화면으로 돌아오세요.")
            return
        }

        setDeviceLabels(devices.map { it.label() }, null)
        val index = devices.indexOfFirst { it.address == selected }
        if (index >= 0) deviceSpinner.setSelection(index)
        if (!linkActive) showStatus(Status.IDLE, null, "PC를 고르고 연결을 누르세요.")
    }

    /** 기기 목록을 채운다. 목록이 비면 선택할 수 없으므로 안내 문구를 대신 보여 준다. */
    private fun setDeviceLabels(labels: List<String>, placeholder: String?) {
        deviceSpinner.adapter = ArrayAdapter(
            this, R.layout.item_spinner, labels.ifEmpty { listOf(placeholder ?: "") },
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        deviceSpinner.isEnabled = labels.isNotEmpty() && !linkActive
        if (!linkActive) connectButton.isEnabled = labels.isNotEmpty()
    }

    private fun connect() {
        val bt = adapter ?: return
        if (!bt.isEnabled) {
            showStatus(Status.ERROR, "블루투스 꺼짐", "블루투스를 켠 뒤 다시 시도하세요.")
            return
        }
        val host = devices.getOrNull(deviceSpinner.selectedItemPosition) ?: run {
            showStatus(Status.ERROR, "기기 없음", "연결할 PC를 고르세요. 목록이 비어 있으면 PC와 먼저 페어링하세요.")
            return
        }
        val pcMode = modeGroup.checkedRadioButtonId == R.id.modePc
        prefs.edit()
            .putString(PREF_MODE, if (pcMode) MODE_PC else MODE_HID)
            .putString(PREF_DEVICE, host.address)
            .apply()

        val onState: (LinkState) -> Unit = { state -> runOnUiThread { applyState(state) } }
        val newOutput = if (pcMode) PcLinkOutput(host, onState) else HidMouseOutput(this, bt, host, onState)
        linkActive = true
        connected = false
        setControlsEnabled(false)
        connectButton.text = getString(R.string.disconnect)
        showStatus(Status.CONNECTING, "연결 중", "${host.label()}에 연결하는 중…")
        pointer.post {
            output = newOutput
            newOutput.start()
        }
    }

    private fun disconnect(message: String) {
        unregisterSensor()
        pointer.post {
            moving = false
            buttons = 0
            output?.close()
            output = null
        }
        linkActive = false
        connected = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setControlsEnabled(true)
        connectButton.text = getString(R.string.connect)
        showStatus(Status.IDLE, null, message)
    }

    private fun applyState(state: LinkState) {
        when (state) {
            is LinkState.Connecting ->
                showStatus(Status.CONNECTING, "연결 중", state.message)

            is LinkState.Connected -> {
                connected = true
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // 연결되기 전부터 패드를 누르고 있었다면 손을 떼지 않아도 바로 움직이게 한다.
                if (padHeld) registerSensor()
                showStatus(Status.CONNECTED, "연결됨", state.message)
            }

            is LinkState.Disconnected -> disconnect(state.message)
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = enabled
        refreshButton.isEnabled = enabled
        // 스피너와 연결 버튼은 loadDevices가 목록 유무에 맞춰 정한다.
        if (enabled) {
            connectButton.isEnabled = devices.isNotEmpty()
            deviceSpinner.isEnabled = devices.isNotEmpty()
        } else {
            deviceSpinner.isEnabled = false
        }
    }

    private fun registerSensor() {
        if (sensorRegistered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return
        sensorRegistered = sensorManager.registerListener(sensorListener, sensor, SENSOR_PERIOD_US, pointer)
        if (!sensorRegistered) showStatus(Status.ERROR, "센서 오류", "회전 센서를 시작하지 못했습니다.")
    }

    private fun unregisterSensor() {
        if (!sensorRegistered) return
        sensorManager.unregisterListener(sensorListener)
        sensorRegistered = false
    }

    private fun setUpSensitivity() {
        val bar = findViewById<SeekBar>(R.id.sensitivity)
        bar.progress = prefs.getInt(PREF_SENSITIVITY, 50)

        fun apply(progress: Int) {
            // 0..100 → 0.35배..2.8배(가운데 1배), 로그 눈금
            val factor = 2.0.pow((progress - 50) / 33.3)
            sensitivityLabel.text = getString(R.string.sensitivity_value, factor)
            pointer.post { mapper.settings = mapper.settings.copy(sensitivity = factor) }
        }

        apply(bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = apply(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                prefs.edit().putInt(PREF_SENSITIVITY, seekBar.progress).apply()
            }
        })
    }

    /** 회전 센서는 연결된 상태에서 패드를 누르고 있는 동안만 켠다(배터리). */
    private fun setUpPad() {
        val pad = findViewById<View>(R.id.pad)
        pad.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    padHeld = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    pointer.post {
                        mapper.reset()
                        moving = true
                    }
                    if (connected) registerSensor()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    padHeld = false
                    unregisterSensor()
                    pointer.post { moving = false }
                }
            }
            true
        }
    }

    private fun setUpButton(id: Int, bit: Int) {
        findViewById<View>(id).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    pointer.post {
                        buttons = buttons or bit
                        output?.send(0, 0, buttons, 0)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    pointer.post {
                        buttons = buttons and bit.inv()
                        output?.send(0, 0, buttons, 0)
                    }
                }
            }
            true
        }
    }

    /** 스크롤 띠: 손가락을 위로 밀면 화면 내용이 위로 올라간다(휴대폰과 같은 방향). */
    private fun setUpScrollStrip() {
        val strip = findViewById<View>(R.id.scroll)
        val stepPx = 48 * resources.displayMetrics.density
        var lastY = 0f
        var carry = 0f
        strip.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    lastY = event.y
                    carry = 0f
                }

                MotionEvent.ACTION_MOVE -> {
                    carry += event.y - lastY
                    lastY = event.y
                    val notches = (carry / stepPx).toInt()
                    if (notches != 0) {
                        carry -= notches * stepPx
                        // 아래로 끌기(+y) = 휠 위로(+)
                        pointer.post { output?.send(0, 0, buttons, notches) }
                        if (abs(notches) > 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.isPressed = false
            }
            true
        }
    }

    private enum class Status { IDLE, CONNECTING, CONNECTED, ERROR }

    /**
     * 상태 배지와 설명 문구를 함께 바꾼다. [detail]이 null이면 기존 설명을 그대로 둔다.
     */
    private fun showStatus(status: Status, badge: String?, detail: String?) {
        val (colorRes, labelRes) = when (status) {
            Status.IDLE -> R.color.text_faint to R.string.status_idle
            Status.CONNECTING -> R.color.warn to R.string.status_connecting
            Status.CONNECTED -> R.color.ok to R.string.status_connected
            Status.ERROR -> R.color.error to R.string.status_error
        }
        val color = getColor(colorRes)
        statusBadge.text = badge ?: getString(labelRes)
        statusBadge.setTextColor(if (status == Status.IDLE) getColor(R.color.text_dim) else color)
        statusDot.backgroundTintList = ColorStateList.valueOf(color)

        if (detail != null) statusDetail.text = detail
        statusDetail.visibility = if (statusDetail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val REQUEST_BLUETOOTH = 1
        private const val SENSOR_PERIOD_US = 10_000 // 100Hz
        private const val PREF_MODE = "mode"
        private const val PREF_DEVICE = "device"
        private const val PREF_SENSITIVITY = "sensitivity"
        private const val MODE_HID = "hid"
        private const val MODE_PC = "pc"
    }
}
