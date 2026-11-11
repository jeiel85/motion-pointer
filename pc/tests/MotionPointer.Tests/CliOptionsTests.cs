namespace MotionPointer.Tests;

public class CliOptionsTests
{
    // 인자는 공백으로 나눈 한 줄로 받는다(특성 인수에 배열을 쓰기 어려워서).
    private static string[] Split(string line) => line.Split(' ', StringSplitOptions.RemoveEmptyEntries);

    [Theory]
    [InlineData("")]
    [InlineData("run")]
    public void Defaults_check_github_for_updates(string line)
    {
        var options = CliOptions.Parse(Split(line));
        Assert.False(options.ShowHelp);
        Assert.True(options.CheckForUpdates);
        Assert.Null(options.UpdateSource);
    }

    [Fact]
    public void No_update_flag_disables_the_check()
    {
        Assert.False(CliOptions.Parse(["run", "--no-update"]).CheckForUpdates);
    }

    [Fact]
    public void Update_source_override_is_kept()
    {
        var options = CliOptions.Parse(["--update-source", @"C:\releases"]);
        Assert.Equal(@"C:\releases", options.UpdateSource);
        Assert.True(options.CheckForUpdates);
    }

    [Theory]
    [InlineData("help")]
    [InlineData("--help")]
    [InlineData("-h")]
    public void Help_is_recognized(string arg)
    {
        Assert.True(CliOptions.Parse([arg]).ShowHelp);
    }

    [Theory]
    [InlineData("bogus")]
    [InlineData("--update-source")]
    [InlineData("run run")]
    public void Invalid_arguments_are_rejected(string line)
    {
        Assert.Throws<ArgumentException>(() => CliOptions.Parse(Split(line)));
    }
}
