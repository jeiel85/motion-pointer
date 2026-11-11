using System.Buffers.Binary;

namespace MotionPointer.Protocol;

/// <summary>폰 → PC 프레임. 규약은 docs/protocol.md.</summary>
internal abstract record PointerFrame
{
    public sealed record Hello(byte Version) : PointerFrame;
    public sealed record Move(short Dx, short Dy) : PointerFrame;
    public sealed record Buttons(byte Mask) : PointerFrame;
    public sealed record Wheel(sbyte Notches) : PointerFrame;
    public sealed record Unknown(byte Type, int Length) : PointerFrame;
}

internal sealed class ProtocolException(string message) : Exception(message);

internal static class PointerProtocol
{
    public const byte Version = 1;
    public static readonly Guid ServiceUuid = new("743082fe-0322-40f3-8b12-844340dda67f");
    public const string ServiceName = "Motion Pointer";

    public const byte TypeHello = 0x01;
    public const byte TypeMove = 0x02;
    public const byte TypeButtons = 0x03;
    public const byte TypeWheel = 0x04;
    public const byte TypeWelcome = 0x81;

    public const byte ButtonLeft = 0x01;
    public const byte ButtonRight = 0x02;
    public const byte ButtonMiddle = 0x04;
    public const byte KnownButtons = ButtonLeft | ButtonRight | ButtonMiddle;

    public static byte[] Welcome() => [TypeWelcome, 1, Version];
}

/// <summary>스트림 바이트를 프레임으로 나눈다. 프레임이 여러 읽기에 걸쳐 와도 이어 붙인다.</summary>
internal sealed class FrameDecoder
{
    private readonly List<byte> _pending = [];

    public List<PointerFrame> Push(ReadOnlySpan<byte> data)
    {
        foreach (var b in data)
        {
            _pending.Add(b);
        }

        var frames = new List<PointerFrame>();
        var offset = 0;
        while (_pending.Count - offset >= 2)
        {
            var type = _pending[offset];
            var length = _pending[offset + 1];
            if (_pending.Count - offset - 2 < length)
            {
                break;
            }

            var payload = _pending.GetRange(offset + 2, length).ToArray();
            frames.Add(Decode(type, payload));
            offset += 2 + length;
        }

        _pending.RemoveRange(0, offset);
        return frames;
    }

    /// <summary>끝나지 않은 프레임의 바이트 수.</summary>
    public int PendingBytes => _pending.Count;

    private static PointerFrame Decode(byte type, byte[] payload)
    {
        return type switch
        {
            PointerProtocol.TypeHello => new PointerFrame.Hello(Expect(payload, 1, "HELLO")[0]),
            PointerProtocol.TypeMove => new PointerFrame.Move(
                BinaryPrimitives.ReadInt16LittleEndian(Expect(payload, 4, "MOVE")),
                BinaryPrimitives.ReadInt16LittleEndian(payload.AsSpan(2))),
            PointerProtocol.TypeButtons => new PointerFrame.Buttons(Expect(payload, 1, "BUTTONS")[0]),
            PointerProtocol.TypeWheel => new PointerFrame.Wheel(unchecked((sbyte)Expect(payload, 1, "WHEEL")[0])),
            _ => new PointerFrame.Unknown(type, payload.Length),
        };
    }

    private static byte[] Expect(byte[] payload, int length, string name)
    {
        if (payload.Length != length)
        {
            throw new ProtocolException($"{name} 프레임 길이가 {payload.Length}입니다(기대값 {length}).");
        }

        return payload;
    }
}
