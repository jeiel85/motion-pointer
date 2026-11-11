using MotionPointer.Protocol;

namespace MotionPointer;

/// <summary>커서·버튼·휠 출력. 실제로는 SendInput, 테스트에서는 기록용 가짜를 쓴다.</summary>
internal interface IMouseOutput
{
    void MoveBy(int dx, int dy);

    /// <summary>previous → current 사이에 바뀐 버튼만 누르거나 뗀다.</summary>
    void SetButtons(byte previous, byte current);

    void Wheel(int notches);
}

/// <summary>
/// 연결 하나에서 받은 프레임을 마우스 입력으로 바꾼다.
/// 일시정지 중에는 이동·휠·누름을 무시하되 뗌은 반영하고, 연결이 끝나면 눌린 버튼을 모두 뗀다.
/// </summary>
internal sealed class PointerSession(IMouseOutput output, Func<bool> isEnabled)
{
    public byte Buttons { get; private set; }
    public byte? PeerVersion { get; private set; }
    public long Moves { get; private set; }
    public long Clicks { get; private set; }

    public void Apply(PointerFrame frame)
    {
        switch (frame)
        {
            case PointerFrame.Hello hello:
                PeerVersion = hello.Version;
                break;

            case PointerFrame.Move move when isEnabled():
                output.MoveBy(move.Dx, move.Dy);
                Moves++;
                break;

            case PointerFrame.Buttons buttons:
                var requested = (byte)(buttons.Mask & PointerProtocol.KnownButtons);
                var next = isEnabled() ? requested : (byte)(Buttons & requested);
                if (next != Buttons)
                {
                    Clicks += System.Numerics.BitOperations.PopCount((uint)(next & ~Buttons));
                    output.SetButtons(Buttons, next);
                    Buttons = next;
                }

                break;

            case PointerFrame.Wheel wheel when isEnabled() && wheel.Notches != 0:
                output.Wheel(wheel.Notches);
                break;
        }
    }

    /// <summary>연결 종료·일시정지 시 눌린 버튼을 모두 뗀다.</summary>
    public void ReleaseAll()
    {
        if (Buttons != 0)
        {
            output.SetButtons(Buttons, 0);
            Buttons = 0;
        }
    }
}
