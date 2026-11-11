using MotionPointer.Protocol;

namespace MotionPointer.Tests;

public class FrameDecoderTests
{
    [Fact]
    public void Decodes_all_known_frames()
    {
        byte[] data =
        [
            0x01, 1, 1,                 // HELLO v1
            0x02, 4, 0x05, 0x00, 0xFE, 0xFF, // MOVE dx=5, dy=-2
            0x03, 1, 0x03,              // BUTTONS left+right
            0x04, 1, 0xFF,              // WHEEL -1
        ];

        var frames = new FrameDecoder().Push(data);

        Assert.Equal(
            [
                new PointerFrame.Hello(1),
                new PointerFrame.Move(5, -2),
                new PointerFrame.Buttons(3),
                new PointerFrame.Wheel(-1),
            ],
            frames);
    }

    [Fact]
    public void Frame_split_across_reads_is_joined()
    {
        var decoder = new FrameDecoder();
        Assert.Empty(decoder.Push([0x02, 4, 0x10]));
        Assert.Equal(3, decoder.PendingBytes);
        var frames = decoder.Push([0x00, 0x20, 0x00]);
        Assert.Equal([new PointerFrame.Move(16, 32)], frames);
        Assert.Equal(0, decoder.PendingBytes);
    }

    [Fact]
    public void Unknown_type_is_skipped_by_length()
    {
        var frames = new FrameDecoder().Push([0x7F, 3, 9, 9, 9, 0x03, 1, 0x01]);
        Assert.Equal([new PointerFrame.Unknown(0x7F, 3), new PointerFrame.Buttons(1)], frames);
    }

    [Fact]
    public void Wrong_length_for_known_type_is_a_protocol_error()
    {
        Assert.Throws<ProtocolException>(() => new FrameDecoder().Push([0x02, 2, 0, 0]));
    }

    [Fact]
    public void Welcome_frame_matches_spec()
    {
        Assert.Equal([0x81, 1, 1], PointerProtocol.Welcome());
    }
}

internal sealed class RecordingMouse : IMouseOutput
{
    public List<string> Events { get; } = [];

    public void MoveBy(int dx, int dy) => Events.Add($"move {dx},{dy}");

    public void SetButtons(byte previous, byte current) => Events.Add($"buttons {previous}->{current}");

    public void Wheel(int notches) => Events.Add($"wheel {notches}");
}

public class PointerSessionTests
{
    [Fact]
    public void Applies_moves_buttons_and_wheel()
    {
        var mouse = new RecordingMouse();
        var session = new PointerSession(mouse, () => true);

        session.Apply(new PointerFrame.Hello(1));
        session.Apply(new PointerFrame.Move(3, -4));
        session.Apply(new PointerFrame.Buttons(PointerProtocol.ButtonLeft));
        session.Apply(new PointerFrame.Buttons(0));
        session.Apply(new PointerFrame.Wheel(2));

        Assert.Equal(["move 3,-4", "buttons 0->1", "buttons 1->0", "wheel 2"], mouse.Events);
        Assert.Equal((byte)1, session.PeerVersion);
        Assert.Equal(1, session.Clicks);
    }

    [Fact]
    public void Repeated_button_state_is_not_resent()
    {
        var mouse = new RecordingMouse();
        var session = new PointerSession(mouse, () => true);
        session.Apply(new PointerFrame.Buttons(1));
        session.Apply(new PointerFrame.Buttons(1));
        Assert.Single(mouse.Events);
    }

    [Fact]
    public void Unknown_button_bits_are_ignored()
    {
        var mouse = new RecordingMouse();
        var session = new PointerSession(mouse, () => true);
        session.Apply(new PointerFrame.Buttons(0xF8));
        Assert.Empty(mouse.Events);
    }

    [Fact]
    public void Paused_session_ignores_input_but_still_releases_buttons()
    {
        var enabled = true;
        var mouse = new RecordingMouse();
        var session = new PointerSession(mouse, () => enabled);

        session.Apply(new PointerFrame.Buttons(PointerProtocol.ButtonLeft));
        enabled = false;
        session.Apply(new PointerFrame.Move(10, 10));
        session.Apply(new PointerFrame.Wheel(1));
        session.Apply(new PointerFrame.Buttons(PointerProtocol.ButtonLeft | PointerProtocol.ButtonRight)); // 오른쪽 누름은 무시
        session.Apply(new PointerFrame.Buttons(0)); // 왼쪽 뗌은 반영

        Assert.Equal(["buttons 0->1", "buttons 1->0"], mouse.Events);
    }

    [Fact]
    public void ReleaseAll_lifts_held_buttons_once()
    {
        var mouse = new RecordingMouse();
        var session = new PointerSession(mouse, () => true);
        session.Apply(new PointerFrame.Buttons(PointerProtocol.ButtonLeft | PointerProtocol.ButtonMiddle));
        session.ReleaseAll();
        session.ReleaseAll();
        Assert.Equal(["buttons 0->5", "buttons 5->0"], mouse.Events);
    }
}

/// <summary>Fail이 켜져 있는 동안 버튼 전달이 실패하는 출력(관리자 권한 창이 입력을 막는 상황).</summary>
internal sealed class SwitchableMouse : IMouseOutput
{
    public bool Fail { get; set; }
    public List<string> Delivered { get; } = [];

    public void MoveBy(int dx, int dy) { }

    public void SetButtons(byte previous, byte current)
    {
        if (Fail)
        {
            throw new System.ComponentModel.Win32Exception(5, "blocked");
        }

        Delivered.Add($"buttons {previous}->{current}");
    }

    public void Wheel(int notches) { }
}

public class PointerSessionFailureTests
{
    [Fact]
    public void Failed_press_is_not_recorded_or_counted()
    {
        var mouse = new SwitchableMouse { Fail = true };
        var session = new PointerSession(mouse, () => true);
        Assert.Throws<System.ComponentModel.Win32Exception>(() => session.Apply(new PointerFrame.Buttons(1)));
        Assert.Equal(0, session.Buttons);
        Assert.Equal(0, session.Clicks);
    }

    [Fact]
    public void Failed_release_is_retried_by_next_release_frame()
    {
        var mouse = new SwitchableMouse();
        var session = new PointerSession(mouse, () => true);
        session.Apply(new PointerFrame.Buttons(PointerProtocol.ButtonLeft));

        mouse.Fail = true; // 일시정지하며 떼려는 순간 입력이 막힘
        Assert.Throws<System.ComponentModel.Win32Exception>(session.ReleaseAll);
        Assert.Equal(PointerProtocol.ButtonLeft, session.Buttons); // 아직 눌린 것으로 기억

        mouse.Fail = false; // 입력이 다시 가능해진 뒤 폰에서 버튼을 뗌
        session.Apply(new PointerFrame.Buttons(0));
        Assert.Equal(0, session.Buttons);
        Assert.Equal(["buttons 0->1", "buttons 1->0"], mouse.Delivered);
    }

    [Fact]
    public void Failed_release_is_retried_on_disconnect()
    {
        var mouse = new SwitchableMouse();
        var session = new PointerSession(mouse, () => true);
        session.Apply(new PointerFrame.Buttons(PointerProtocol.ButtonRight));

        mouse.Fail = true;
        Assert.Throws<System.ComponentModel.Win32Exception>(session.ReleaseAll);
        mouse.Fail = false;
        session.ReleaseAll(); // 연결 종료 시 정리

        Assert.Equal(0, session.Buttons);
        Assert.Equal(["buttons 0->2", "buttons 2->0"], mouse.Delivered);
    }
}
