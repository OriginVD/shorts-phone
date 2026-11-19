# 쇼츠 플레이어 (핸드폰)

핸드폰에 넣어 둔 쇼츠 영상을 골라서 **한 개를 N번씩 반복한 뒤 다음 영상으로 넘어가는** 웹 재생기예요.

- 주소: https://originvd.github.io/shorts-phone/
- 영상은 핸드폰 안에서만 읽어요. 인터넷으로 올라가지 않아요.
- 설정(반복 횟수, 섞기, 무한반복, 볼륨)은 브라우저에 저장돼요.

## 주의
- 이 저장소의 `index.html`은 **복사본**이에요. 원본은 비공개 저장소 `Shorts`의 `phone.html`이에요.
  고칠 때는 원본을 고치고 `Shorts\deploy-phone.ps1`로 다시 올려요.

## 안드로이드 앱
같은 재생기를 앱으로도 쓸 수 있어요. (`android-app/` 폴더)

- 받기: https://github.com/OriginVD/shorts-phone/releases/latest/download/shorts-player.apk
  - 폰에서 위 주소를 열어 받은 뒤 열면 설치돼요. 처음 한 번은 "출처를 알 수 없는 앱 설치"를 허용해야 해요.
- `index.html`이나 `android-app/`이 바뀌어 올라가면 GitHub Actions가 APK를 새로 만들어 릴리스에 올려요.
  새 APK를 받아 설치하면 기존 앱 위에 덮어써지고, 넣어 둔 영상과 설정은 그대로 남아요.
- 앱은 늘 같은 열쇠(`android-app/app-signing.jks`)로 서명해요. 이 파일을 지우거나 바꾸면 덮어 설치가 안 되고,
  앱을 지웠다 다시 깔아야 해요(그때는 넣어 둔 영상도 지워져요).
- 앱에서는 **링크로 영상 받기**도 돼요. 링크를 붙여 넣고 [⬇ 받기]를 누르거나, 틱톡/유튜브 앱에서
  "공유" → "쇼츠 플레이어"를 고르면 받아서 목록에 넣어요. (안에서 yt-dlp를 써요. `android-app/web/app-extra.js`,
  `ShortsDownloaderPlugin.java`) 이 기능은 앱에만 있고 웹 재생기(`index.html`)는 그대로예요.
- APK는 64비트 ARM 폰용만 만들어요. (yt-dlp/ffmpeg 때문에 크기가 커서)
- `deploy-phone.ps1`로 다시 올릴 때 `android-app/`, `.github/` 폴더가 지워지지 않게 주의하세요.
