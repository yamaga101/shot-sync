# shot-sync

## Session Identity
**THIS SESSION IS FOR: shot-sync**
Compact 後も shot-sync の作業のみ継続すること。他プロジェクトの作業はしない。

## Quick Reference
- Android (Kotlin + Jetpack Compose) アプリ。`/Pictures/Screenshots/` を watch して
  Google Drive の指定フォルダに instant 自動 upload する PoC ツール。
- Autosync (3rd party) の自前置換。yamaga101 carve-out 配下、k35 混入禁止。
- v0.1.0 = MVP: sign-in / folder ID 設定 / 手動 1 枚 upload / FileObserver + auto upload

## Google アカウント (carve-out)
- **yamaga101@gmail.com** に統合
- GCP project: `gen-lang-client-0371763712` (ev-manager と同じ project を流用、OAuth
  Client は別途 Android type で発行する。同 project は許容、Drive API 既 enable)
- 詳細: `docs/specs/system-yamaga101-carveout.md`
- **絶対禁止**: コード/スクリプト/パス/トークンに `shigaki@k35.jp` /
  `GoogleDrive-shigaki@k35` / その他 k35 関連を一切混入させない
- 自動チェック: `npm run check-k35` (CI: `.github/workflows/k35-check.yml`)

## Setup (clone 後)
```bash
npm install      # 何も入っていない (Android 専用、JS deps なし)。npm scripts のみ。
npm run setup-hooks   # core.hooksPath を .githooks に向ける + chmod
```

## Build & Run
```bash
./gradlew :app:assembleDebug     # APK ビルド
npm run release-apk              # GitHub Releases に upload
```

実機 install:
- `https://github.com/yamaga101/shot-sync/releases/latest/download/app-debug.apk`
- これは ev-manager と同じ canonical URL ルール
