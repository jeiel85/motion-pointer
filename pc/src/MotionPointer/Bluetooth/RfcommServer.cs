using System.Runtime.InteropServices;
using System.Text;
using MotionPointer.Protocol;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace MotionPointer.Bluetooth;

/// <summary>
/// "Motion Pointer" RFCOMM 서비스를 SDP에 광고하고 폰 연결을 받는다.
/// 인증·암호화된 링크만 받으므로 페어링된 기기만 연결된다. 새 연결이 오면 이전 연결을 닫는다.
/// </summary>
internal sealed class RfcommServer : IAsyncDisposable
{
    private const ushort SdpServiceNameAttributeId = 0x100;
    private const byte SdpTextType8BitLength = (4 << 3) | 5; // 문자열, 길이 1바이트

    private readonly RfcommServiceProvider _provider;
    private readonly StreamSocketListener _listener;
    private readonly Func<StreamSocket, CancellationToken, Task> _handleConnection;
    private readonly Action<string> _log;
    private readonly object _gate = new();
    private (StreamSocket Socket, CancellationTokenSource Cancel, Task Task)? _active;

    private RfcommServer(
        RfcommServiceProvider provider,
        StreamSocketListener listener,
        Func<StreamSocket, CancellationToken, Task> handleConnection,
        Action<string> log)
    {
        _provider = provider;
        _listener = listener;
        _handleConnection = handleConnection;
        _log = log;
    }

    public static async Task<RfcommServer> StartAsync(Func<StreamSocket, CancellationToken, Task> handleConnection, Action<string> log)
    {
        RfcommServiceProvider provider;
        try
        {
            provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(PointerProtocol.ServiceUuid));
        }
        catch (Exception ex) when (ex is COMException or UnauthorizedAccessException)
        {
            throw new BluetoothUnavailableException($"블루투스 RFCOMM 서비스를 만들 수 없습니다(블루투스가 꺼져 있거나 어댑터가 없음): {ex.Message}", ex);
        }

        var listener = new StreamSocketListener();
        var server = new RfcommServer(provider, listener, handleConnection, log);
        listener.ConnectionReceived += server.OnConnectionReceived;
        try
        {
            await listener.BindServiceNameAsync(
                provider.ServiceId.AsString(),
                SocketProtectionLevel.BluetoothEncryptionWithAuthentication);

            var name = Encoding.UTF8.GetBytes(PointerProtocol.ServiceName);
            using var writer = new DataWriter();
            writer.WriteByte(SdpTextType8BitLength);
            writer.WriteByte((byte)name.Length);
            writer.WriteBytes(name);
            provider.SdpRawAttributes.Add(SdpServiceNameAttributeId, writer.DetachBuffer());
            provider.StartAdvertising(listener);
        }
        catch (Exception ex) when (ex is COMException or UnauthorizedAccessException or InvalidOperationException)
        {
            // 블루투스 라디오가 꺼져 있으면 여기서 실패한다. 반쯤 열린 리스너를 남기지 않는다.
            listener.ConnectionReceived -= server.OnConnectionReceived;
            listener.Dispose();
            throw new BluetoothUnavailableException(
                $"블루투스 서비스를 시작할 수 없습니다. 블루투스가 켜져 있는지 확인하세요: {ex.Message}", ex);
        }

        return server;
    }

    private void OnConnectionReceived(StreamSocketListener sender, StreamSocketListenerConnectionReceivedEventArgs args)
    {
        var socket = args.Socket;
        var cancel = new CancellationTokenSource();
        (StreamSocket Socket, CancellationTokenSource Cancel, Task Task)? previous;
        lock (_gate)
        {
            previous = _active;
            var task = Task.Run(() => RunConnectionAsync(socket, cancel.Token));
            _active = (socket, cancel, task);
        }

        if (previous is { } old)
        {
            _log("새 연결이 들어와 이전 연결을 닫습니다.");
            old.Cancel.Cancel();
            old.Socket.Dispose();
        }
    }

    private async Task RunConnectionAsync(StreamSocket socket, CancellationToken cancellationToken)
    {
        try
        {
            await _handleConnection(socket, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            socket.Dispose();
        }
    }

    public async ValueTask DisposeAsync()
    {
        _provider.StopAdvertising();
        _listener.ConnectionReceived -= OnConnectionReceived;
        _listener.Dispose();

        (StreamSocket Socket, CancellationTokenSource Cancel, Task Task)? active;
        lock (_gate)
        {
            active = _active;
            _active = null;
        }

        if (active is { } connection)
        {
            await connection.Cancel.CancelAsync().ConfigureAwait(false);
            connection.Socket.Dispose();
            await connection.Task.ConfigureAwait(false);
            connection.Cancel.Dispose();
        }
    }
}

internal sealed class BluetoothUnavailableException(string message, Exception inner) : Exception(message, inner);
