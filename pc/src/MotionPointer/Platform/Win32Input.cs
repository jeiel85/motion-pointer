using System.ComponentModel;
using System.Runtime.InteropServices;
using MotionPointer.Protocol;

namespace MotionPointer.Platform;

/// <summary>
/// SendInput으로 커서를 움직이고 버튼·휠을 보낸다.
/// 상대 이동은 윈도우 "포인터 정확도 향상"(가속)의 영향을 받으므로, 현재 위치 + 이동량을 절대 좌표로 보낸다.
/// </summary>
internal sealed class Win32MouseOutput : IMouseOutput
{
    public void MoveBy(int dx, int dy) => Win32Mouse.MoveBy(dx, dy);

    public void SetButtons(byte previous, byte current) => Win32Mouse.SetButtons(previous, current);

    public void Wheel(int notches) => Win32Mouse.Wheel(notches);
}

internal static class Win32Mouse
{
    private const uint InputMouse = 0;
    private const uint MouseEventMove = 0x0001;
    private const uint MouseEventLeftDown = 0x0002;
    private const uint MouseEventLeftUp = 0x0004;
    private const uint MouseEventRightDown = 0x0008;
    private const uint MouseEventRightUp = 0x0010;
    private const uint MouseEventMiddleDown = 0x0020;
    private const uint MouseEventMiddleUp = 0x0040;
    private const uint MouseEventWheel = 0x0800;
    private const uint MouseEventVirtualDesk = 0x4000;
    private const uint MouseEventAbsolute = 0x8000;
    private const int WheelDelta = 120;

    private const int SmXVirtualScreen = 76;
    private const int SmYVirtualScreen = 77;
    private const int SmCxVirtualScreen = 78;
    private const int SmCyVirtualScreen = 79;

    private static readonly (byte Bit, uint Down, uint Up)[] ButtonFlags =
    [
        (PointerProtocol.ButtonLeft, MouseEventLeftDown, MouseEventLeftUp),
        (PointerProtocol.ButtonRight, MouseEventRightDown, MouseEventRightUp),
        (PointerProtocol.ButtonMiddle, MouseEventMiddleDown, MouseEventMiddleUp),
    ];

    public static void MoveBy(int dx, int dy)
    {
        if (dx == 0 && dy == 0)
        {
            return;
        }

        if (!GetCursorPos(out var position))
        {
            throw new Win32Exception(Marshal.GetLastPInvokeError(), "커서 위치를 읽지 못했습니다.");
        }

        var left = GetSystemMetrics(SmXVirtualScreen);
        var top = GetSystemMetrics(SmYVirtualScreen);
        var width = Math.Max(GetSystemMetrics(SmCxVirtualScreen), 2);
        var height = Math.Max(GetSystemMetrics(SmCyVirtualScreen), 2);

        var x = Math.Clamp(position.X + dx, left, left + width - 1);
        var y = Math.Clamp(position.Y + dy, top, top + height - 1);

        var input = new Input
        {
            Type = InputMouse,
            Mouse = new MouseInput
            {
                Dx = (int)Math.Round((x - left) * 65535.0 / (width - 1)),
                Dy = (int)Math.Round((y - top) * 65535.0 / (height - 1)),
                Flags = MouseEventMove | MouseEventAbsolute | MouseEventVirtualDesk,
            },
        };
        Send([input]);
    }

    public static void SetButtons(byte previous, byte current)
    {
        var inputs = new List<Input>();
        foreach (var (bit, down, up) in ButtonFlags)
        {
            var was = (previous & bit) != 0;
            var now = (current & bit) != 0;
            if (was != now)
            {
                inputs.Add(new Input { Type = InputMouse, Mouse = new MouseInput { Flags = now ? down : up } });
            }
        }

        if (inputs.Count > 0)
        {
            Send([.. inputs]);
        }
    }

    public static void Wheel(int notches)
    {
        if (notches == 0)
        {
            return;
        }

        Send(
        [
            new Input
            {
                Type = InputMouse,
                Mouse = new MouseInput { Flags = MouseEventWheel, MouseData = unchecked((uint)(notches * WheelDelta)) },
            },
        ]);
    }

    private static void Send(Input[] inputs)
    {
        var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());
        if (sent != inputs.Length)
        {
            // UIPI: 관리자 권한 창 위에서는 일반 권한 앱의 입력이 막힌다.
            throw new Win32Exception(Marshal.GetLastPInvokeError(), "SendInput이 입력을 보내지 못했습니다(관리자 권한 창 위일 수 있습니다).");
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int Dx;
        public int Dy;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public IntPtr ExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct Input
    {
        [FieldOffset(0)] public uint Type;
        [FieldOffset(8)] public MouseInput Mouse;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, Input[] inputs, int size);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetCursorPos(out Point point);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);
}

internal static class Dpi
{
    private static readonly IntPtr PerMonitorAwareV2 = new(-4);

    /// <summary>모니터별 DPI 인식. 이렇게 해야 커서 좌표가 배율과 무관한 실제 픽셀이 된다.</summary>
    public static void EnablePerMonitorAwareness()
    {
        if (!SetProcessDpiAwarenessContext(PerMonitorAwareV2))
        {
            var error = Marshal.GetLastPInvokeError();
            const int accessDenied = 5; // 이미 설정된 경우
            if (error != accessDenied)
            {
                throw new Win32Exception(error, "DPI 인식 모드를 설정하지 못했습니다.");
            }
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetProcessDpiAwarenessContext(IntPtr value);
}

/// <summary>전역 단축키 하나를 전용 스레드의 메시지 루프로 받는다.</summary>
internal sealed class GlobalHotkey : IDisposable
{
    public const uint ModAlt = 0x0001;
    public const uint ModControl = 0x0002;
    private const uint ModNoRepeat = 0x4000;
    private const uint WmHotkey = 0x0312;
    private const uint WmQuit = 0x0012;
    private const int HotkeyId = 0x4150;

    private readonly Thread _thread;
    private readonly TaskCompletionSource<string?> _registered = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private uint _threadId;

    /// <summary>등록에 실패하면 예외 대신 <see cref="RegistrationError"/>에 이유를 남긴다(앱은 단축키 없이도 동작).</summary>
    public GlobalHotkey(uint modifiers, uint virtualKey, Action onPressed)
    {
        _thread = new Thread(() => Run(modifiers, virtualKey, onPressed)) { IsBackground = true, Name = "hotkey" };
        _thread.Start();
        RegistrationError = _registered.Task.GetAwaiter().GetResult();
    }

    public string? RegistrationError { get; }

    private void Run(uint modifiers, uint virtualKey, Action onPressed)
    {
        _threadId = GetCurrentThreadId();
        if (!RegisterHotKey(IntPtr.Zero, HotkeyId, modifiers | ModNoRepeat, virtualKey))
        {
            _registered.SetResult(new Win32Exception(Marshal.GetLastPInvokeError()).Message);
            return;
        }

        _registered.SetResult(null);
        try
        {
            while (GetMessageW(out var message, IntPtr.Zero, 0, 0) > 0)
            {
                if (message.Message == WmHotkey && message.WParam == HotkeyId)
                {
                    onPressed();
                }
            }
        }
        finally
        {
            UnregisterHotKey(IntPtr.Zero, HotkeyId);
        }
    }

    public void Dispose()
    {
        if (RegistrationError is null && _threadId != 0)
        {
            PostThreadMessageW(_threadId, WmQuit, IntPtr.Zero, IntPtr.Zero);
            _thread.Join(TimeSpan.FromSeconds(1));
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Msg
    {
        public IntPtr Hwnd;
        public uint Message;
        public IntPtr WParam;
        public IntPtr LParam;
        public uint Time;
        public int PointX;
        public int PointY;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hwnd, int id, uint modifiers, uint virtualKey);

    [DllImport("user32.dll")]
    private static extern bool UnregisterHotKey(IntPtr hwnd, int id);

    [DllImport("user32.dll")]
    private static extern int GetMessageW(out Msg message, IntPtr hwnd, uint filterMin, uint filterMax);

    [DllImport("user32.dll")]
    private static extern bool PostThreadMessageW(uint threadId, uint message, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
}
