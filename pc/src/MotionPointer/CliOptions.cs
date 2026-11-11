namespace MotionPointer;

internal sealed record CliOptions(bool ShowHelp, bool CheckForUpdates, string? UpdateSource)
{
    /// <summary>
    /// motion-pointer [run] [--no-update] [--update-source 경로|URL]
    /// </summary>
    public static CliOptions Parse(IReadOnlyList<string> args)
    {
        var help = false;
        var checkForUpdates = true;
        string? updateSource = null;

        for (var i = 0; i < args.Count; i++)
        {
            switch (args[i])
            {
                case "run" when i == 0:
                    break;
                case "help" when i == 0:
                case "-h" or "--help" or "/?":
                    help = true;
                    break;
                case "--no-update":
                    checkForUpdates = false;
                    break;
                case "--update-source":
                    if (i + 1 >= args.Count || string.IsNullOrWhiteSpace(args[i + 1]))
                    {
                        throw new ArgumentException("--update-source 뒤에 폴더 경로나 URL이 필요합니다.");
                    }

                    updateSource = args[++i];
                    break;
                default:
                    throw new ArgumentException($"알 수 없는 명령이나 옵션: {args[i]}");
            }
        }

        return new CliOptions(help, checkForUpdates, updateSource);
    }
}
