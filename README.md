# KDE Connect ClipSync (Windows 11 ↔ Android 이미지/텍스트 클립보드 공유)

KDE Connect 프로토콜 기반의 **경량 클립보드 전용 동기화 도구**입니다.  
기존 공식 KDE Connect 앱이 텍스트만 지원하는 한계를 해결하여, **Windows 11의 이미지 클립보드(Win+Shift+S 스크린샷 등)를 Android 기기로 실시간 전송**하고 Android 클립보드에 즉시 붙여넣을 수 있도록 구현되었습니다.

---

## 💡 기존 KDE Connect와의 차이점 & 해결 원리

1. **기존 공식 앱의 한계**:
   - KDE Connect 공식 프로토콜의 `kdeconnect.clipboard` 패킷은 `content` 문자열(String)만 처리합니다.
   - Windows의 비트맵/PNG 클립보드 포맷이나 Android의 ContentProvider 이미지 클립보드 처리가 누락되어 있었습니다.

2. **본 프로젝트의 해결 방식**:
   - **Windows 11**: Win32 Clipboard API 시퀀스 감시를 통해 텍스트뿐만 아니라 스크린샷/이미지(`CF_DIB`, `CF_BITMAP`, `PNG`) 변경을 즉시 감지하여 Base64로 패키징 (`kdeconnect.clipboard.image`).
   - **Android**: 백그라운드 포그라운드 서비스가 패킷을 수신하면 내부 캐시에 PNG로 복원한 뒤 `FileProvider`를 통해 `content://...` URI를 발급하고 `ClipData.newUri(contentResolver, "Image", uri)`로 시스템 클립보드에 등록합니다.
   - **결과**: 사용자가 Windows 11에서 `Win + Shift + S`로 영역을 캡처하면, 스마트폰(Android)의 카카오톡, 메시지, 삼성 노트 등의 입력창에서 바로 **[붙여넣기]**를 눌러 이미지를 보낼 수 있습니다.

---

## 📂 프로젝트 구조

```
kdeconnect-image-clipboard/
├── windows/                        # Windows 11 클라이언트 (설치 불필요, 원클릭 실행)
│   ├── src/
│   │   ├── main.js                 # KDE Connect UDP Discovery & TCP 소켓 통신
│   │   ├── clipboard_watcher.ps1  # Windows 11 Win32 텍스트/이미지 클립보드 감시
│   │   └── clipboard_writer.ps1   # 역방향 클립보드 주입기
│   ├── package.json
│   └── start.bat                   # 더블클릭 실행 스크립트
├── android/                        # Android 클라이언트 (네이티브 Kotlin + Material3)
│   ├── app/
│   │   ├── src/main/AndroidManifest.xml
│   │   └── src/main/java/com/kdeconnect/clipboard/
│   │       ├── MainActivity.kt
│   │       └── ClipboardSyncService.kt (포그라운드 서비스 + FileProvider)
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── .github/workflows/build-apk.yml # GitHub 무료 클라우드 APK 자동 빌드 파이프라인
```

---

## 🚀 사용 방법

### 1. Windows 11 실행
1. PC와 Android 스마트폰이 **동일한 Wi-Fi 네트워크**(공유기)에 연결되어 있는지 확인합니다.
2. `windows/start.bat` 파일을 더블 클릭하여 실행합니다.
   - Node.js가 설치되어 있으므로 추가 의존성 설치 없이 즉시 실행됩니다.
   - 콘솔에 PC 이름 및 UDP/TCP 1716 포트 리스닝 상태가 표시됩니다.

### 2. Android APK 빌드 및 설치

#### 방법 A: GitHub Actions로 원클릭 자동 빌드 (추천: 로컬 개발환경 불필요)
1. 이 프로젝트 폴더를 본인의 GitHub 저장소에 Push합니다.
2. 저장소의 **[Actions]** 탭으로 이동하면 `Build Android APK` 워크플로우가 자동으로 실행됩니다.
3. 빌드가 완료되면(약 1~2분 소요) **Artifacts** 섹션에서 `KDEConnect-ClipSync-Android-APK`를 다운로드하여 스마트폰에 설치합니다.

#### 방법 B: Android Studio에서 직접 빌드
1. Android Studio에서 `android` 디렉터리를 엽니다.
2. 상단 메뉴 `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`를 클릭하여 생성된 `app-debug.apk`를 기기에 설치합니다.

---

## 📱 동작 확인
1. Android 앱을 실행하고 알림 권한을 허용합니다 (화면에 초록색 불과 함께 Windows 11 PC 이름이 뜹니다).
2. Windows 11에서 `Win + Shift + S` 키를 눌러 화면 일부를 캡처합니다.
3. Android 화면에 수신된 이미지가 미리보기로 나타나며, 카카오톡이나 메신저 입력창에서 길게 터치 후 **[붙여넣기]**를 누르면 캡처한 이미지가 바로 입력됩니다.
