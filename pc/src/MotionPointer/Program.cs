using System.Diagnostics;
using System.Text;
using MotionPointer.Bluetooth;
using MotionPointer.Platform;
using MotionPointer.Protocol;
using Windows.Networking.Sockets;

namespace MotionPointer;

internal static class Program
{
    private const uint VkF9 = 0x78;

    private static async Task<int> Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        if (args.Length > 0 && args[0] is "help" or "-h" or "--help" or "/?")
        {
            PrintHelp();
            return 0;
        }

        if (args.Length > 0 && args[0] != "run")
        {
            Console.Error.WriteLine($"알 수 없는 명령: {args[0]}");
            PrintHelp();
            return 64;
        }

        using var cancellation = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cancellation.Cancel();
        };

        Dpi.EnablePerMonitorAwareness();
        var app = new App();
        try
        {
            return await app.RunAsync(cancellation.Token);
        }
        catch (BluetoothUnavailableException ex)
        {
            Console.Error.WriteLine($"오류: {ex.Message}");
            return 2;
        }
    }

    private static void PrintHelp()
    {
        Console.WriteLine("""
            motion-pointer — Motion Pointer 폰 앱의 "PC 앱" 모드 입력을 받아 커서를 움직입니다.

            사용법:
              motion-pointer [run]   블루투스로 폰 연결을 기다립니다(기본).

            준비:
              1. 폰과 이 PC가 블루투스로 페어링되어 있어야 합니다.
              2. 폰 앱에서 연결 방식을 "PC 앱"으로 고르고 이 PC를 선택해 연결합니다.

            실행 중 단축키:
              Ctrl+Alt+F9   커서 제어 일시정지/재개
              Ctrl+C        종료
            """);
    }

    private sealed class App
    {
        private int _enabled = 1;
        private PointerSession? _session;

        private bool IsEnabled => Volatile.Read(ref _enabled) == 1;

        public async Task<int> RunAsync(CancellationToken cancellationToken)
        {
            using var hotkey = new GlobalHotkey(GlobalHotkey.ModControl | GlobalHotkey.ModAlt, VkF9, TogglePause);
            if (hotkey.RegistrationError is { } hotkeyError)
            {
                Console.WriteLine($"단축키 Ctrl+Alt+F9 등록 실패({hotkeyError}). 일시정지 없이 동작합니다.");
            }

            await using var server = await RfcommServer.StartAsync(HandleConnectionAsync, Log);
            Log($"블루투스 서비스 \"{PointerProtocol.ServiceName}\" 대기 중. 폰 앱에서 \"PC 앱\" 모드로 이 PC({Environment.MachineName})를 선택해 연결하세요.");
            Log("Ctrl+Alt+F9 일시정지, Ctrl+C 종료.");

            try
            {
                await Task.Delay(Timeout.Infinite, cancellationToken);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
            }

            Console.WriteLine();
            Log("종료합니다.");
            return 0;
        }

        private void TogglePause()
        {
            int original, updated;
            do
            {
                original = Volatile.Read(ref _enabled);
                updated = original ^ 1;
            }
            while (Interlocked.CompareExchange(ref _enabled, updated, original) != original);

            var enabled = updated == 1;
            if (!enabled)
            {
                _session?.ReleaseAll();
            }

            Console.WriteLine();
            Log(enabled ? "▶ 커서 제어 재개" : "⏸ 커서 제어 일시정지 (Ctrl+Alt+F9로 재개)");
        }

        private async Task HandleConnectionAsync(StreamSocket socket, CancellationToken cancellationToken)
        {
            var peer = socket.Information.RemoteHostName?.DisplayName ?? "알 수 없는 기기";
            Console.WriteLine();
            Log($"연결됨: {peer}");

            var session = new PointerSession(new Win32MouseOutput(), () => IsEnabled);
            _session = session;
            var decoder = new FrameDecoder();
            string? lastInputError = null;
            var lastStatus = 0L;

            try
            {
                await using var input = socket.InputStream.AsStreamForRead(0);
                await using var output = socket.OutputStream.AsStreamForWrite(0);
                await output.WriteAsync(PointerProtocol.Welcome(), cancellationToken);
                await output.FlushAsync(cancellationToken);

                var buffer = new byte[1024];
                while (!cancellationToken.IsCancellationRequested)
                {
                    var count = await input.ReadAsync(buffer, cancellationToken);
                    if (count <= 0)
                    {
                        break;
                    }

                    foreach (var frame in decoder.Push(buffer.AsSpan(0, count)))
                    {
                        try
                        {
                            session.Apply(frame);
                            lastInputError = null;
                        }
                        catch (System.ComponentModel.Win32Exception ex)
                        {
                            if (ex.Message != lastInputError)
                            {
                                Console.WriteLine();
                                Log($"입력 전달 실패: {ex.Message}");
                                lastInputError = ex.Message;
                            }
                        }
                    }

                    var now = Stopwatch.GetTimestamp();
                    if (now - lastStatus > Stopwatch.Frequency / 4)
                    {
                        lastStatus = now;
                        var state = IsEnabled ? "● 동작" : "⏸ 정지";
                        var blocked = lastInputError is null ? "" : " | 입력 차단됨(관리자 권한 창?)";
                        Console.Write($"\r{state} | {peer} | 이동 {session.Moves} | 클릭 {session.Clicks}{blocked}    ");
                    }
                }
            }
            catch (ProtocolException ex)
            {
                Console.WriteLine();
                Log($"규약 위반으로 연결을 끊습니다: {ex.Message}");
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
            }
            catch (Exception ex) when (ex is IOException or ObjectDisposedException or System.Runtime.InteropServices.COMException)
            {
                Console.WriteLine();
                Log($"연결 오류: {ex.Message}");
            }
            finally
            {
                session.ReleaseAll();
                if (ReferenceEquals(_session, session))
                {
                    _session = null;
                }

                Console.WriteLine();
                Log($"연결 끊김: {peer}" + (session.PeerVersion is null ? " (HELLO를 받지 못함)" : ""));
            }
        }

        private static void Log(string message) => Console.WriteLine($"{DateTime.Now:HH:mm:ss} {message}");
    }
}
