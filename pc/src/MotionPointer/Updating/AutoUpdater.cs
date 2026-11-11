using Velopack;
using Velopack.Sources;

namespace MotionPointer.Updating;

/// <summary>
/// 시작할 때 GitHub 릴리스에서 새 버전을 확인하고, 있으면 내려받아 적용한 뒤 다시 시작한다.
/// 설치·업데이트는 Velopack이 맡는다(패키지 해시 검증, 실행 중 exe 교체, 재시작).
/// 확인·다운로드가 실패해도 앱은 현재 버전으로 계속 실행한다.
/// </summary>
internal static class AutoUpdater
{
    public const string RepositoryUrl = "https://github.com/jeiel85/motion-pointer";
    public static readonly TimeSpan CheckTimeout = TimeSpan.FromSeconds(15);

    /// <param name="sourceOverride">업데이트 저장소 대신 쓸 로컬 폴더나 URL(시험·자체 배포용). null이면 GitHub 릴리스.</param>
    /// <param name="restartArgs">업데이트 후 다시 시작할 때 넘길 인자.</param>
    public static async Task UpdateIfAvailableAsync(
        string? sourceOverride,
        string[] restartArgs,
        Action<string> log,
        CancellationToken cancellationToken)
    {
        var manager = sourceOverride is null
            ? new UpdateManager(new GithubSource(RepositoryUrl, accessToken: null, prerelease: false))
            : new UpdateManager(sourceOverride);

        if (!manager.IsInstalled)
        {
            log("설치본이 아니라서(개발 빌드·직접 실행) 자동 업데이트를 건너뜁니다.");
            return;
        }

        var source = sourceOverride ?? "GitHub 릴리스";
        log($"업데이트 확인 중… ({source})");

        UpdateInfo? update;
        using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
        {
            timeout.CancelAfter(CheckTimeout);
            try
            {
                update = await manager.CheckForUpdatesAsync().WaitAsync(timeout.Token);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                log($"업데이트 확인이 {CheckTimeout.TotalSeconds:0}초 안에 끝나지 않아 현재 버전으로 실행합니다.");
                return;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // 네트워크·GitHub API 제한·피드 형식 오류 등. 업데이트 실패가 앱 사용을 막으면 안 된다.
                log($"업데이트를 확인하지 못해 현재 버전으로 실행합니다: {ex.Message}");
                return;
            }
        }

        if (update is null)
        {
            log($"최신 버전입니다 ({manager.CurrentVersion}).");
            return;
        }

        var target = update.TargetFullRelease;
        log($"새 버전을 내려받습니다: {manager.CurrentVersion} → {target.Version}");
        var lastReported = -1;
        try
        {
            await manager.DownloadUpdatesAsync(update, percent =>
            {
                // 10% 단위로만 표시
                var step = percent / 10;
                if (step != Interlocked.Exchange(ref lastReported, step))
                {
                    Console.Write($"\r  내려받는 중 {percent,3}%");
                }
            }, cancellationToken);
            Console.WriteLine();
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            Console.WriteLine();
            log($"새 버전을 내려받지 못해 현재 버전으로 실행합니다: {ex.Message}");
            return;
        }

        log($"새 버전({target.Version})을 적용하고 다시 시작합니다.");
        // 이 호출은 현재 프로세스를 끝내고 업데이터가 파일을 바꾼 뒤 새 버전을 실행한다.
        manager.ApplyUpdatesAndRestart(target, restartArgs);
    }
}
