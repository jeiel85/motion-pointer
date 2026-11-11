using System.Diagnostics;
using System.Text;
using MotionPointer.Bluetooth;
using MotionPointer.Platform;
using MotionPointer.Protocol;
using MotionPointer.Updating;
using Velopack;
using Windows.Networking.Sockets;

namespace MotionPointer;

internal static class Program
{
    private const uint VkF9 = 0x78;

    private static string AppVersion =>
        typeof(Program).Assembly.GetName().Version?.ToString(3) ?? "unknown";

    private static int Main(string[] args)
    {
        // 설치·제거·업데이트 직후 Velopack이 이 프로세스를 훅으로 실행하므로 진입점 첫 줄에서 호출한다.
        // (async Main 안에 두면 컴파일러가 만든 상태 머신 안으로 들어가 vpk가 진입점으로 인식하지 못한다.)
        // 내려받아 둔 업데이트를 여기서 자동 적용하면 실패(Update.exe 실행 차단 등)를 잡을 수 없어
        // 앱이 매번 시작 전에 종료될 수 있다. 적용은 실패를 처리하는 AutoUpdater에서만 한다.
        VelopackApp.Build().SetAutoApplyOnStartup(false).Run();
        return RunAsync(args).GetAwaiter().GetResult();
    }

    private static async Task<int> RunAsync(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        CliOptions options;
        try
        {
            options = CliOptions.Parse(args);
        }
        catch (ArgumentException ex)
        {
            Console.Error.WriteLine(ex.Message);
            PrintHelp();
            return 64;
        }

        if (options.ShowHelp)
        {
            PrintHelp();
            return 0;
        }

        using var cancellation = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cancellation.Cancel();
        };

        Console.WriteLine($"Motion Pointer {AppVersion}");
        if (options.CheckForUpdates)
        {
            try
            {
                await AutoUpdater.UpdateIfAvailableAsync(options.UpdateSource, args, Log, cancellation.Token);
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
            {
                return 0;
            }
        }

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

            옵션:
              --no-update                  시작할 때 업데이트를 확인하지 않습니다.
              --update-source 경로|URL     GitHub 대신 이 폴더·주소에서 업데이트를 찾습니다(시험·자체 배포용).

            설치본은 시작할 때마다 GitHub 릴리스에서 새 버전을 확인하고, 있으면 자동으로 업데이트한 뒤 다시 시작합니다.

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
        // 단축키 스레드(일시정지)와 연결 스레드가 함께 본다.
        private volatile PointerSession? _session;

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
                ReleaseButtons(_session);
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
                ReleaseButtons(session);
                if (ReferenceEquals(_session, session))
                {
                    _session = null;
                }

                Console.WriteLine();
                Log($"연결 끊김: {peer}" + (session.PeerVersion is null ? " (HELLO를 받지 못함)" : ""));
            }
        }

        /// <summary>
        /// 눌린 버튼을 뗀다. 단축키 스레드나 finally에서 부르므로 입력 전달 실패가 앱을 죽이지 않게 기록만 한다.
        /// </summary>
        private static void ReleaseButtons(PointerSession? session)
        {
            try
            {
                session?.ReleaseAll();
            }
            catch (System.ComponentModel.Win32Exception ex)
            {
                Console.WriteLine();
                Log($"버튼을 떼지 못했습니다(관리자 권한 창 위일 수 있음): {ex.Message}");
            }
        }

    }

    private static void Log(string message) => Console.WriteLine($"{DateTime.Now:HH:mm:ss} {message}");
}
