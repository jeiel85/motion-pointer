package com.jeiel85.motionpointer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
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
    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
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
    private var connected = false
    private var sensorRegistered = false
    private var devices: List<BluetoothDevice> = emptyList()

    private val sensorListener = object : SensorEventListener {
        private val q = FloatArray(4)

        override fun onSensorChanged(event: SensorEvent) {
            if (!moving) return
            SensorManager.getQuaternionFromVector(q, event.values)
            val delta = mapper.update(Quat(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()), event.timestamp)
            if (delta.dx != 0 || delta.dy != 0) output?.send(delta.dx, delta.dy, buttons, 0)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        modeGroup = findViewById(R.id.mode)
        deviceSpinner = findViewById(R.id.device)
        connectButton = findViewById(R.id.connect)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)

        modeGroup.check(if (prefs.getString(PREF_MODE, MODE_HID) == MODE_PC) R.id.modePc else R.id.modeHid)
        connectButton.setOnClickListener { if (output == null) connect() else disconnect("연결을 끊었습니다.") }
        setUpSensitivity()
        setUpPad()
        setUpButton(R.id.leftButton, Buttons.LEFT)
        setUpButton(R.id.rightButton, Buttons.RIGHT)
        setUpScrollStrip()

        if (sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null) {
            showStatus("이 폰에는 자이로 기반 회전 센서가 없어 쓸 수 없습니다.")
            connectButton.isEnabled = false
            return
        }

        if (hasBluetoothPermission()) loadDevices() else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (hasBluetoothPermission()) loadDevices()
        else showStatus("'근처 기기' 권한이 있어야 블루투스로 연결할 수 있습니다. 설정 > 앱에서 허용해 주세요.")
    }

    override fun onResume() {
        super.onResume()
        if (connected) registerSensor()
    }

    override fun onPause() {
        super.onPause()
        unregisterSensor()
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

    private fun loadDevices() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            showStatus("블루투스를 켠 뒤 앱을 다시 열어 주세요.")
            return
        }

        // 컴퓨터를 앞에, 그다음 이름순.
        devices = bt.bondedDevices.sortedWith(
            compareBy<BluetoothDevice>({ it.bluetoothClass?.majorDeviceClass != BluetoothClass.Device.Major.COMPUTER }, { it.label() }))
        if (devices.isEmpty()) {
            showStatus("페어링된 기기가 없습니다. 먼저 폰 블루투스 설정에서 PC와 페어링하세요.")
            return
        }

        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, devices.map { it.label() })
        val saved = devices.indexOfFirst { it.address == prefs.getString(PREF_DEVICE, null) }
        if (saved >= 0) deviceSpinner.setSelection(saved)
        showStatus("PC를 고르고 연결을 누르세요.")
    }

    private fun connect() {
        val bt = adapter ?: return
        val host = devices.getOrNull(deviceSpinner.selectedItemPosition) ?: run {
            showStatus("연결할 PC를 고르세요.")
            return
        }
        val pcMode = modeGroup.checkedRadioButtonId == R.id.modePc
        prefs.edit().putString(PREF_MODE, if (pcMode) MODE_PC else MODE_HID).putString(PREF_DEVICE, host.address).apply()

        val onState: (LinkState) -> Unit = { state -> runOnUiThread { applyState(state) } }
        val newOutput = if (pcMode) PcLinkOutput(host, onState) else HidMouseOutput(this, bt, host, onState)
        setControlsEnabled(false)
        connectButton.text = getString(R.string.disconnect)
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
        connected = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setControlsEnabled(true)
        connectButton.text = getString(R.string.connect)
        showStatus(message)
    }

    private fun applyState(state: LinkState) {
        when (state) {
            is LinkState.Connecting -> showStatus(state.message)
            is LinkState.Connected -> {
                connected = true
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                registerSensor()
                showStatus(state.message)
            }
            is LinkState.Disconnected -> disconnect(state.message)
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = enabled
        deviceSpinner.isEnabled = enabled
    }

    private fun registerSensor() {
        if (sensorRegistered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return
        sensorRegistered = sensorManager.registerListener(sensorListener, sensor, SENSOR_PERIOD_US, pointer)
        if (!sensorRegistered) showStatus("회전 센서를 시작하지 못했습니다.")
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

    private fun setUpPad() {
        val pad = findViewById<View>(R.id.pad)
        pad.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    pointer.post {
                        mapper.reset()
                        moving = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
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

    private fun showStatus(message: String) {
        status.text = message
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
