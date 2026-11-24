using System.ComponentModel;
using System.Runtime.InteropServices;

namespace MotionPointer.Platform;

/// <summary>
/// 절대 포인팅 중 주 모니터 모서리에 흰 마커 4개를 띄운다.
/// WS_EX_LAYERED의 색 키로 검은 배경을 투명하게 만들고, WS_EX_TRANSPARENT로 입력을 통과시킨다.
/// 창은 전용 메시지 루프 스레드에서만 만들고 조작한다.
/// </summary>
internal sealed class MarkerOverlay : IDisposable
{
    private const string WindowClassName = "MotionPointerMarkerOverlay";
    private const uint WsAppSet = 0x8001;
    private const uint WsAppQuit = 0x8002;
    private const uint WmDestroy = 0x0002;
    private const uint WmClose = 0x0010;
    private const uint WmPaint = 0x000F;

    private const uint WsPopup = 0x80000000;
    private const uint WsExLayered = 0x00080000;
    private const uint WsExTransparent = 0x00000020;
    private const uint WsExTopmost = 0x00000008;
    private const uint WsExToolwindow = 0x00000080;
    private const uint WsExNoactivate = 0x08000000;
    private const byte LwaColorKey = 0x01;
    private const int SwHide = 0;
    private const int SwShowNoActivate = 8;

    private const int SmXVirtualScreen = 76;
    private const int SmYVirtualScreen = 77;
    private const int SmCxScreen = 0;
    private const int SmCyScreen = 1;

    private const double Inset = 0.06; // MarkerLayout.INSET과 같아야 한다.
    private const double SizeFraction = 0.05;

    private readonly Thread _thread;
    private readonly TaskCompletionSource<IntPtr> _handle = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly WndProc _windowProc = WindowProc;
    private volatile IntPtr _window;
    private int _disposed;

    public MarkerOverlay()
    {
        _thread = new Thread(Run) { IsBackground = true, Name = "marker-overlay" };
        _thread.Start();
        _window = _handle.Task.GetAwaiter().GetResult();
        if (_window == IntPtr.Zero)
        {
            throw new Win32Exception(Marshal.GetLastPInvokeError(), "마커 창을 만들지 못했습니다.");
        }
    }

    public void Show(bool visible)
    {
        var window = _window;
        if (window == IntPtr.Zero || Volatile.Read(ref _disposed) != 0)
        {
            return;
        }

        if (visible)
        {
            var left = GetSystemMetrics(SmXVirtualScreen);
            var top = GetSystemMetrics(SmYVirtualScreen);
            var width = Math.Max(GetSystemMetrics(SmCxScreen), 2);
            var height = Math.Max(GetSystemMetrics(SmCyScreen), 2);
            SetWindowPos(window, IntPtr.Zero, left, top, width, height, 0x0004 /* SWP_NOZORDER */);
            InvalidateRect(window, IntPtr.Zero, true);
        }

        PostMessageW(window, WsAppSet, visible ? (IntPtr)1 : IntPtr.Zero, IntPtr.Zero);
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        var window = _window;
        if (window != IntPtr.Zero)
        {
            PostMessageW(window, WsAppQuit, IntPtr.Zero, IntPtr.Zero);
        }

        if (!_thread.Join(TimeSpan.FromSeconds(2)))
        {
            // 메시지 루프가 죽었으면 창만이라도 닫는다.
            if (window != IntPtr.Zero)
            {
                PostMessageW(window, WmClose, IntPtr.Zero, IntPtr.Zero);
            }
        }
    }

    private void Run()
    {
        var instance = GetModuleHandle(null);
        var windowClass = new WndClassEx
        {
            cbSize = (uint)Marshal.SizeOf<WndClassEx>(),
            style = 0,
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(_windowProc),
            cbClsExtra = 0,
            cbWndExtra = 0,
            hInstance = instance,
            hIcon = IntPtr.Zero,
            hCursor = IntPtr.Zero,
            hbrBackground = IntPtr.Zero,
            lpszClassName = WindowClassName,
            hIconSm = IntPtr.Zero,
        };

        if (RegisterClassExW(ref windowClass) == 0)
        {
            _handle.TrySetResult(IntPtr.Zero);
            return;
        }

        var window = CreateWindowExW(
            WsExLayered | WsExTransparent | WsExTopmost | WsExToolwindow | WsExNoactivate,
            WindowClassName, "Motion Pointer markers", WsPopup,
            0, 0, 1, 1, IntPtr.Zero, IntPtr.Zero, instance, IntPtr.Zero);

        if (window != IntPtr.Zero)
        {
            SetLayeredWindowAttributes(window, 0, 255, LwaColorKey);
            ShowWindow(window, SwHide);
        }

        _window = window;
        _handle.TrySetResult(window);
        if (window == IntPtr.Zero)
        {
            return;
        }

        while (GetMessageW(out var message, IntPtr.Zero, 0, 0) > 0)
        {
            TranslateMessage(ref message);
            DispatchMessageW(ref message);
        }
    }

    private static IntPtr WindowProc(IntPtr window, uint message, IntPtr wParam, IntPtr lParam)
    {
        switch (message)
        {
            case WmPaint:
                Paint(window);
                return IntPtr.Zero;

            case WsAppSet:
                ShowWindow(window, wParam == IntPtr.Zero ? SwHide : SwShowNoActivate);
                return IntPtr.Zero;

            case WsAppQuit:
                PostQuitMessage(0);
                return IntPtr.Zero;

            case WmClose:
                DestroyWindow(window);
                return IntPtr.Zero;

            case WmDestroy:
                // 창이 외부에서 닫히거나 Dispose 폴백으로 WM_CLOSE가 와도 메시지 루프를 끝낸다.
                PostQuitMessage(0);
                return IntPtr.Zero;

            default:
                return DefWindowProcW(window, message, wParam, lParam);
        }
    }

    private static void Paint(IntPtr window)
    {
        var paint = default(PaintStruct);
        var deviceContext = BeginPaint(window, out paint);
        if (deviceContext == IntPtr.Zero)
        {
            return;
        }

        try
        {
            var width = paint.RcPaint.Right - paint.RcPaint.Left;
            var height = paint.RcPaint.Bottom - paint.RcPaint.Top;
            var black = GetStockObject(4 /* BLACK_BRUSH */);
            var white = GetStockObject(0 /* WHITE_BRUSH */);
            FillRect(deviceContext, ref paint.RcPaint, black);

            var size = Math.Max(12, (int)Math.Round(Math.Min(width, height) * SizeFraction));
            var insetX = (int)Math.Round(width * Inset);
            var insetY = (int)Math.Round(height * Inset);
            int[] centersX = [insetX, width - insetX, width - insetX, insetX];
            int[] centersY = [insetY, insetY, height - insetY, height - insetY];
            for (var i = 0; i < 4; i++)
            {
                var rectangle = new Rect
                {
                    Left = centersX[i] - size / 2,
                    Top = centersY[i] - size / 2,
                    Right = centersX[i] + size / 2,
                    Bottom = centersY[i] + size / 2,
                };
                FillRect(deviceContext, ref rectangle, white);
            }
        }
        finally
        {
            EndPaint(window, ref paint);
        }
    }

    private delegate IntPtr WndProc(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WndClassEx
    {
        public uint cbSize;
        public uint style;
        public IntPtr lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string lpszClassName;
        public IntPtr hIconSm;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PaintStruct
    {
        public IntPtr Hdc;
        public bool Erase;
        public Rect RcPaint;
        public bool Restore;
        public bool Return;
        public IntPtr Reserved1;
        public IntPtr Reserved2;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Message
    {
        public IntPtr Hwnd;
        public uint MessageId;
        public IntPtr WParam;
        public IntPtr LParam;
        public uint Time;
        public Point Point;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassExW(ref WndClassEx windowClass);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowExW(
        uint extendedStyle, string className, string windowName, uint style,
        int x, int y, int width, int height,
        IntPtr parent, IntPtr menu, IntPtr instance, IntPtr parameter);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(IntPtr window, IntPtr insertAfter, int x, int y, int width, int height, uint flags);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetLayeredWindowAttributes(IntPtr window, uint colorKey, byte alpha, byte flags);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool ShowWindow(IntPtr window, int command);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool PostMessageW(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyWindow(IntPtr window);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern int GetMessageW(out Message message, IntPtr window, uint filterMin, uint filterMax);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref Message message);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessageW(ref Message message);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DefWindowProcW(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr BeginPaint(IntPtr window, out PaintStruct paint);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool EndPaint(IntPtr window, ref PaintStruct paint);

    [DllImport("user32.dll")]
    private static extern IntPtr FillRect(IntPtr deviceContext, ref Rect rectangle, IntPtr brush);

    [DllImport("user32.dll")]
    private static extern IntPtr GetStockObject(int index);

    [DllImport("user32.dll")]
    private static extern bool InvalidateRect(IntPtr window, IntPtr rectangle, bool erase);

    [DllImport("user32.dll")]
    private static extern void PostQuitMessage(int exitCode);

    [DllImport("kernel32.dll", CharSet = CharSet.Auto)]
    private static extern IntPtr GetModuleHandle(string? moduleName);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);
}
