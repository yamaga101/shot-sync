# shot-sync

> 個人 Android アプリ: `/Pictures/Screenshots/` を watch して Google Drive へ
> 自動 upload する。Autosync (3rd party) の自前置換。

## なぜ作った？

- Autosync は 3rd party の OAuth Client / scope / 維持に依存
- 自前なら Drive API quota も自分の枠、carve-out 規約を自然に守れる
- Drive folder watcher は Capacitor / 既存 plugin で扱いきれず、native Android が筋

## Stack

- Kotlin 2.0 + Jetpack Compose (Material 3)
- WorkManager (upload retry / queue)
- DataStore (settings 永続化)
- Google Sign-In + Drive REST API (`drive.file` scope)
- FileObserver + Foreground Service (常駐監視)

## Setup (初回)

### 開発環境
- Android Studio (Hedgehog 以降推奨、JDK 21 同梱)
- AGP 8.7 / Gradle 8.11
- 端末: API 28+ (Android 9+)

### GCP OAuth Client (Android type) を作成

`gen-lang-client-0371763712` (yamaga101 GCP project — ev-manager と共用) で:

1. APIs & Services → Credentials → Create Credentials → OAuth client ID
2. **Application type: Android**
3. **Package name**: `jp.gmail.yamaga101.shotsync`
4. **SHA-1**: debug keystore の fingerprint
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android | grep SHA1
   ```
5. ev-manager と同じ OAuth consent screen を流用 (既に「外部 / Testing」設定済、
   yamaga101 が test user 登録済)

> Android OAuth Client は Desktop と違い JSON ダウンロード不要。package + SHA-1 で
> Google が認識する。

### ローカル install

```bash
git clone git@github.com:yamaga101/shot-sync.git
cd shot-sync
npm run setup-hooks   # pre-commit に check-no-k35 を仕込む
./gradlew :app:assembleDebug
```

### 端末配布

```bash
npm run release-apk
# → https://github.com/yamaga101/shot-sync/releases/latest/download/app-debug.apk
```

## Usage

1. アプリ起動 → 「サインイン」で yamaga101 を選択
2. 送り先 Drive folder ID を入力 (Drive web で対象フォルダ開いた URL 末尾)
   - 例: `1DBOVzP2x8qS8ThsihMutfH_8IgzSMOWa`
3. 「最新 1 枚を upload」で疎通確認 (P1)
4. 「自動アップロード」を ON → foreground service が watch 開始 (P2)
5. 端末でスクショ → 数秒後に Drive へ反映

## Carve-out 例外

yamaga101@gmail.com のみ。`shigaki@k35.jp` 系混入は **絶対バグ扱い**。
`docs/specs/system-yamaga101-carveout.md` 参照。

CI で `scripts/check-no-k35.sh` が push / PR ごとに走り、混入を block する。
