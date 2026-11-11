package com.jeiel85.motionpointer

import java.util.UUID

/** PC 앱 모드 규약. docs/protocol.md 와 PC 앱의 PointerProtocol.cs 와 같아야 한다. */
object PointerProtocol {
    const val VERSION = 1
    val SERVICE_UUID: UUID = UUID.fromString("743082fe-0322-40f3-8b12-844340dda67f")

    const val TYPE_HELLO = 0x01
    const val TYPE_MOVE = 0x02
    const val TYPE_BUTTONS = 0x03
    const val TYPE_WHEEL = 0x04
    const val TYPE_WELCOME = 0x81

    fun hello() = byteArrayOf(TYPE_HELLO.toByte(), 1, VERSION.toByte())

    fun move(dx: Int, dy: Int): ByteArray {
        val x = dx.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val y = dy.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return byteArrayOf(
            TYPE_MOVE.toByte(), 4,
            x.toByte(), (x shr 8).toByte(),
            y.toByte(), (y shr 8).toByte(),
        )
    }

    fun buttons(mask: Int) = byteArrayOf(TYPE_BUTTONS.toByte(), 1, (mask and Buttons.ALL).toByte())

    fun wheel(notches: Int) = byteArrayOf(TYPE_WHEEL.toByte(), 1, notches.coerceIn(-127, 127).toByte())
}

object Buttons {
    const val LEFT = 0x01
    const val RIGHT = 0x02
    const val MIDDLE = 0x04
    const val ALL = LEFT or RIGHT or MIDDLE
}

/**
 * HID 마우스 모드: 표준 블루투스 HID 마우스 보고서.
 * 버튼 3개 + X/Y 16비트 상대값 + 휠 8비트. 윈도우·리눅스·맥의 기본 HID 드라이버가 그대로 인식한다.
 */
object HidMouse {
    const val REPORT_ID = 1

    val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x02,       // Usage (Mouse)
        0xA1, 0x01,       // Collection (Application)
        0x85, REPORT_ID,  //   Report ID
        0x09, 0x01,       //   Usage (Pointer)
        0xA1, 0x00,       //   Collection (Physical)
        0x05, 0x09,       //     Usage Page (Button)
        0x19, 0x01,       //     Usage Minimum (1)
        0x29, 0x03,       //     Usage Maximum (3)
        0x15, 0x00,       //     Logical Minimum (0)
        0x25, 0x01,       //     Logical Maximum (1)
        0x95, 0x03,       //     Report Count (3)
        0x75, 0x01,       //     Report Size (1)
        0x81, 0x02,       //     Input (Data, Variable, Absolute)
        0x95, 0x01,       //     Report Count (1)
        0x75, 0x05,       //     Report Size (5)
        0x81, 0x03,       //     Input (Constant) — 패딩
        0x05, 0x01,       //     Usage Page (Generic Desktop)
        0x09, 0x30,       //     Usage (X)
        0x09, 0x31,       //     Usage (Y)
        0x16, 0x01, 0x80, //     Logical Minimum (-32767)
        0x26, 0xFF, 0x7F, //     Logical Maximum (32767)
        0x75, 0x10,       //     Report Size (16)
        0x95, 0x02,       //     Report Count (2)
        0x81, 0x06,       //     Input (Data, Variable, Relative)
        0x09, 0x38,       //     Usage (Wheel)
        0x15, 0x81,       //     Logical Minimum (-127)
        0x25, 0x7F,       //     Logical Maximum (127)
        0x75, 0x08,       //     Report Size (8)
        0x95, 0x01,       //     Report Count (1)
        0x81, 0x06,       //     Input (Data, Variable, Relative)
        0xC0,             //   End Collection
        0xC0,             // End Collection
    ).map { it.toByte() }.toByteArray()

    /** 보고서 본문(보고서 ID 제외, 6바이트). */
    fun report(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray {
        val x = dx.coerceIn(-32767, 32767)
        val y = dy.coerceIn(-32767, 32767)
        return byteArrayOf(
            (buttons and Buttons.ALL).toByte(),
            x.toByte(), (x shr 8).toByte(),
            y.toByte(), (y shr 8).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
        )
    }
}
