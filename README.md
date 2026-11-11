# motion-pointer

갤럭시 S24 같은 안드로이드 폰을 **공중 포인터(에어 마우스)** 로 씁니다. 패드를 누른 채 폰을 기울이면 커서가 움직이고, 화면 버튼으로 클릭·스크롤합니다.

## 연결 방식 두 가지

| | 블루투스 마우스(HID) | PC 앱 |
| --- | --- | --- |
| PC 쪽 설치 | 없음 | Motion Pointer 설치(자동 업데이트) |
| 동작하는 곳 | 윈도우·우분투·맥·태블릿·TV 등 블루투스 마우스를 받는 모든 기기 | 윈도우 |
| 커서 이동 | 운영체제의 마우스 가속이 함께 적용됨 | 가속 없이 앱이 계산한 그대로 |
| 일시정지 | 폰에서 연결 끊기 | PC에서 `Ctrl+Alt+F9` |
| 처음 설정 | PC에서 폰을 다시 페어링해야 할 수 있음 | 기존 페어링 그대로 |

두 방식 모두 커서 이동량은 **폰에서 같은 방식으로 계산**하므로 조작감이 같습니다.

## 폰 앱 (`android/`)

- **패드를 누르고 있는 동안** 폰을 기울이면 커서가 움직입니다. 손을 떼면 멈추므로, 마우스를 들어 옮기듯 다시 잡고 이어서 움직일 수 있습니다.
- 좌우는 지면에 수직인 축 기준 회전입니다. 그래서 손목을 비틀어도 가로 이동이 섞이지 않습니다.
- 위아래는 "가리키는 쪽을 들어 올리기"입니다. 폰을 눕혀 위쪽 끝으로 가리키든, 세워서 뒷면으로 가리키든 똑같이 동작합니다.
- 왼쪽·오른쪽 클릭 버튼은 누르고 있으면 드래그가 됩니다. 가운데 스크롤 띠는 휴대폰 화면처럼 손가락 방향으로 스크롤합니다.
- 감도 슬라이더는 0.35배~2.8배이고, 가운데가 1배입니다.
- 앱이 화면에 떠 있는 동안만 동작합니다(화면 꺼짐 방지 포함).

### 블루투스 마우스 모드 처음 연결

1. 폰과 PC가 이미 페어링되어 있어도, PC는 이 폰을 "휴대폰"으로만 알고 있습니다. 앱에서 연결했는데 실패하면 **PC 블루투스 설정에서 폰을 제거**한 뒤 다시 페어링합니다.
2. 다시 페어링하는 동안 폰 앱의 "블루투스 마우스" 연결을 켜 두면, PC가 이 폰을 마우스로도 인식합니다.

### PC 앱 모드

1. [릴리스](https://github.com/jeiel85/motion-pointer/releases/latest)에서 `MotionPointer-win-Setup.exe`를 받아 실행합니다. 관리자 권한 없이 사용자 폴더(`%LOCALAPPDATA%\MotionPointer`)에 설치되고 시작 메뉴에 "Motion Pointer"가 생깁니다. 설치 없이 쓰려면 `MotionPointer-win-Portable.zip`을 풀어서 실행합니다.
2. 폰 앱에서 "PC 앱"을 고르고 그 PC를 선택해 연결합니다.
3. 이미 페어링된 기기만 연결됩니다(인증·암호화된 블루투스 링크). 같은 Wi-Fi의 다른 사람이 마우스를 조작할 수 없습니다.

#### 자동 업데이트

설치본(포터블 포함)은 시작할 때마다 GitHub 릴리스에서 새 버전을 확인합니다. 새 버전이 있으면 내려받아 적용하고 다시 시작합니다. 바뀐 부분만 담은 델타 패키지를 쓰고, 내려받은 파일은 해시로 검증합니다.

- 확인이나 다운로드가 실패하면(오프라인, GitHub 응답 없음 등) 현재 버전으로 그대로 실행합니다. 확인은 최대 15초까지만 기다립니다.
- `motion-pointer --no-update`: 이번 실행에서는 업데이트를 확인하지 않습니다.
- `motion-pointer --update-source <폴더|URL>`: GitHub 대신 지정한 곳에서 업데이트를 찾습니다(시험·자체 배포용).
- 개발 중 `dotnet run`이나 `dist\` 실행처럼 설치본이 아닌 경우에는 업데이트를 건너뜁니다.
- 제거는 윈도우 설정 > 앱에서 "Motion Pointer"를 지우면 됩니다.

## 빌드

### 안드로이드 (JDK 17, Android SDK 36)

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

릴리스 APK(`assembleRelease`)는 저장소 루트의 `.keystore/release-signing.properties`와 키스토어가 있어야 만들어집니다. 둘 다 git에서 제외되어 있고, 백업은 `G:\내 드라이브\모바일 앱 개발\Motion Pointer\릴리즈 키스토어\`에 있습니다. 서명 정보가 없으면 서명 안 된 APK를 만들지 않고 빌드가 실패합니다.

이 PC에서는 Gradle 9.3.1의 Kotlin DSL 캐시가 설정 스크립트를 잘못 불러오는 문제가 있어 9.5.1을 씁니다. Git Bash의 `./gradlew`는 빈 인자를 하나 더 넘기는 문제가 있으니 `gradlew.bat`을 쓰세요.

### PC 앱 (.NET 10 SDK)

```powershell
cd pc
dotnet test MotionPointer.slnx
dotnet publish src/MotionPointer -c Release -o dist
dist\motion-pointer.exe
```

배포용 설치본·포터블·업데이트 패키지는 [Velopack](https://velopack.io)으로 만듭니다.

```powershell
dotnet tool install -g vpk --version 1.2.161   # Velopack 패키지 버전과 맞춘다
cd pc
.\build-release.ps1 -Version 0.2.0            # csproj <Version>과 같아야 함
```

`pc\release\`에 생긴 파일(`*-Setup.exe`, `*-Portable.zip`, `*.nupkg`, `releases.win.json`, `assets.win.json`, `RELEASES`)을 **모두** GitHub 릴리스에 올려야 설치본이 새 버전을 찾습니다. 스크립트는 직전 릴리스를 받아 델타 패키지도 만듭니다.

## 구조

```
android/   폰 앱(Kotlin, 외부 라이브러리 없음)
  PointerMapper.kt   자세 변화 → 커서 이동량(두 모드 공통)
  Protocols.kt       PC 앱 프레임, HID 마우스 보고서
  PointerOutputs.kt  HID 마우스 / PC 앱 연결
pc/        PC 앱(C#, Windows)
  Bluetooth/RfcommServer.cs   "Motion Pointer" RFCOMM 서비스
  PointerSession.cs           프레임 → 커서·버튼·휠(연결이 끊기면 버튼 자동 해제)
docs/protocol.md   폰 ↔ PC 앱 통신 규약
```

## 지나온 길

처음에는 에어팟 케이스를 마우스로 쓰는 맥 데모(@instantricecook)를 윈도우로 옮기려 했습니다. 그런데 윈도우에서 에어팟 센서를 읽으려면 서명되지 않은 커널 드라이버(MagicAAP)와 테스트 모드가 필요해서 접었습니다([DECISIONS.md](DECISIONS.md) D3). 360 오디오를 지원하는 갤럭시 버즈는 윈도우 일반 앱으로도 센서를 읽을 수 있어서, 후속 과제로 남겨 두었습니다([BACKLOG.md](BACKLOG.md)).
