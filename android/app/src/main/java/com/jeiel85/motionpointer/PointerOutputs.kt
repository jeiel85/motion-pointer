package com.jeiel85.motionpointer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

sealed interface LinkState {
    data class Connecting(val message: String) : LinkState
    data class Connected(val message: String) : LinkState
    data class Disconnected(val message: String) : LinkState
}

/** 포인터 출력. [send]·[close]는 포인터 스레드에서만 호출한다. */
interface PointerOutput {
    fun start()

    /** 현재 버튼 상태와 함께 이동량·휠을 보낸다. */
    fun send(dx: Int, dy: Int, buttons: Int, wheel: Int)

    /** 버튼을 모두 뗀 뒤 연결을 닫는다. */
    fun close()
}

/**
 * 폰을 표준 블루투스 HID 마우스로 등록한다. PC 쪽 설치가 필요 없다.
 * 호스트(PC)가 이 폰을 HID 장치로 알아야 하므로, 처음에는 PC에서 페어링을 다시 해야 할 수 있다.
 */
@SuppressLint("MissingPermission") // 호출 전에 MainActivity가 BLUETOOTH_CONNECT 권한을 확인한다.
class HidMouseOutput(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val host: BluetoothDevice,
    private val onState: (LinkState) -> Unit,
) : PointerOutput {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var connected = false
    @Volatile private var closed = false

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed) return
            if (registered) {
                onState(LinkState.Connecting("HID 마우스 등록됨. ${host.label()}에 연결하는 중…"))
                if (hid?.connect(host) != true) {
                    onState(LinkState.Disconnected("${host.label()}에 HID 연결을 요청하지 못했습니다."))
                }
            } else {
                connected = false
                onState(LinkState.Disconnected("HID 마우스 등록이 해제되었습니다."))
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed || device.address != host.address) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    onState(LinkState.Connected("${host.label()}에 블루투스 마우스로 연결됨"))
                }
                BluetoothProfile.STATE_CONNECTING ->
                    onState(LinkState.Connecting("${host.label()}에 연결하는 중…"))
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    onState(LinkState.Disconnected(
                        "HID 연결이 끊겼습니다. 계속 실패하면 PC 블루투스 설정에서 이 폰을 제거한 뒤 다시 페어링하세요."))
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, HidMouse.report(0, 0, 0, 0))
        }
    }

    override fun start() {
        onState(LinkState.Connecting("HID 서비스를 준비하는 중…"))
        val requested = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val device = proxy as BluetoothHidDevice
                hid = device
                if (closed) {
                    adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
                    return
                }
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Motion Pointer", "Phone air mouse", "motion-pointer",
                    BluetoothHidDevice.SUBCLASS1_MOUSE, HidMouse.DESCRIPTOR,
                )
                if (!device.registerApp(sdp, null, null, executor, callback)) {
                    onState(LinkState.Disconnected("HID 마우스 등록에 실패했습니다. 다른 앱이 HID 기능을 쓰고 있는지 확인하세요."))
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                connected = false
                if (!closed) onState(LinkState.Disconnected("블루투스 HID 서비스가 중단되었습니다."))
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!requested) onState(LinkState.Disconnected("이 폰은 블루투스 HID 장치 기능을 지원하지 않습니다."))
    }

    override fun send(dx: Int, dy: Int, buttons: Int, wheel: Int) {
        if (!connected) return
        hid?.sendReport(host, HidMouse.REPORT_ID, HidMouse.report(buttons, dx, dy, wheel))
    }

    override fun close() {
        closed = true
        hid?.let { device ->
            if (connected) device.sendReport(host, HidMouse.REPORT_ID, HidMouse.report(0, 0, 0, 0))
            device.disconnect(host)
            device.unregisterApp()
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hid = null
        connected = false
        executor.shutdown()
    }
}

/**
 * PC 앱 모드: 페어링된 PC의 "Motion Pointer" RFCOMM 서비스에 연결해 이동·버튼·휠 프레임을 보낸다.
 * 규약은 docs/protocol.md.
 */
@SuppressLint("MissingPermission")
class PcLinkOutput(
    private val host: BluetoothDevice,
    private val onState: (LinkState) -> Unit,
) : PointerOutput {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false
    private var sentButtons = 0

    override fun start() {
        onState(LinkState.Connecting("${host.label()}의 PC 앱에 연결하는 중…"))
        thread(name = "pc-link", isDaemon = true) {
            val s = try {
                host.createRfcommSocketToServiceRecord(PointerProtocol.SERVICE_UUID).also { it.connect() }
            } catch (e: IOException) {
                if (!closed) onState(LinkState.Disconnected("연결 실패: ${host.label()}에서 PC 앱(motion-pointer)이 실행 중인지 확인하세요. (${e.message})"))
                return@thread
            }
            socket = s
            try {
                val out = s.outputStream
                out.write(PointerProtocol.hello())
                out.flush()
                output = out
                readUntilClosed(s.inputStream)
                if (!closed) onState(LinkState.Disconnected("PC 앱 연결이 끊겼습니다."))
            } catch (e: IOException) {
                if (!closed) onState(LinkState.Disconnected("PC 앱 연결이 끊겼습니다: ${e.message}"))
            } finally {
                output = null
                runCatching { s.close() }
            }
        }
    }

    private fun readUntilClosed(input: InputStream) {
        while (true) {
            val type = input.read()
            val length = input.read()
            if (type < 0 || length < 0) return
            val payload = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(payload, read, length - read)
                if (n < 0) return
                read += n
            }
            if (type == PointerProtocol.TYPE_WELCOME && length == 1) {
                onState(LinkState.Connected("${host.label()}의 PC 앱에 연결됨 (규약 v${payload[0]})"))
            }
        }
    }

    override fun send(dx: Int, dy: Int, buttons: Int, wheel: Int) {
        val out = output ?: return
        val frames = ByteArrayOutputStream(16)
        if (dx != 0 || dy != 0) frames.write(PointerProtocol.move(dx, dy))
        if (buttons != sentButtons) frames.write(PointerProtocol.buttons(buttons))
        if (wheel != 0) frames.write(PointerProtocol.wheel(wheel))
        if (frames.size() == 0) return
        try {
            out.write(frames.toByteArray())
            sentButtons = buttons
        } catch (e: IOException) {
            output = null
            runCatching { socket?.close() }
            if (!closed) onState(LinkState.Disconnected("PC 앱으로 보내지 못했습니다: ${e.message}"))
        }
    }

    override fun close() {
        closed = true
        output?.let { out -> runCatching { if (sentButtons != 0) out.write(PointerProtocol.buttons(0)) } }
        output = null
        runCatching { socket?.close() }
        socket = null
    }
}

@SuppressLint("MissingPermission")
fun BluetoothDevice.label(): String = name ?: address
