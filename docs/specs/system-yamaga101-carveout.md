---
visibility: internal
---

# yamaga101 carve-out (shot-sync)

## これは何？

shot-sync は **yamaga101@gmail.com 配下の個人作業** として運用する。
会社 (`shigaki@k35.jp`) の Workspace / GCP リソースに依存しない・混入しない。

## なぜ独立させるのか？

- shot-sync は端末の screenshot を Drive に upload する個人 utility
- ev-manager と並んで個人 dev 環境のための tooling
- 会社情報を扱わないため、code / token / path に k35 が混入する理由がない
- 退職時の切り離しを容易にするため、最初から carve-out しておく

## 対象リソース

| リソース | 場所 |
|---|---|
| GitHub repo | `yamaga101/shot-sync` (public) |
| GCP project | `gen-lang-client-0371763712` (yamaga101)。**ev-manager と同じ project を流用** (Drive API + Gmail API enabled 済、OAuth consent screen 構成済) |
| OAuth Client | `EV Manager CLI` とは別途 Android type を発行 (package: `jp.gmail.yamaga101.shotsync` + 端末の SHA-1) |
| Drive 監視先 | 各 user が任意指定。default は `1DBOVzP2x8qS8ThsihMutfH_8IgzSMOWa` (ev-manager debug screenshots と兼用) |
| 端末 watch path | `/Pictures/Screenshots/` または `/DCIM/Screenshots/` (端末の OS 設定による) |
| Build / 配布 | GitHub Releases (`/releases/latest/download/app-debug.apk`) |

## 絶対禁止

- **コード / スクリプト / パス / トークンに `shigaki@k35.jp` を混入させない**
- **`GoogleDrive-shigaki@k35` の path も使わない**
- **会社 GitHub (`kyusanko-corp/...`) からは clone も push も pull もしない**

違反は絶対バグ扱い。コメント / docs 中で歴史的に「混入禁止である」と説明する文章は許容。

## 自動チェック

`scripts/check-no-k35.sh` で grep ベースの混入検査。違反検出で exit 1。

- **pre-commit hook** (`.githooks/pre-commit` → `npm run setup-hooks` で活性化)
- **GitHub Actions** (`.github/workflows/k35-check.yml`、push / PR で自動)

## ev-manager との関係

| 観点 | ev-manager | shot-sync |
|---|---|---|
| Google account | yamaga101 | yamaga101 |
| GCP project | gen-lang-client-0371763712 | gen-lang-client-0371763712 (共用) |
| OAuth Client | EV Manager CLI (Desktop, 未使用化) | shot-sync (Android, drive.file scope) |
| Drive 用途 | 配布廃止 (GitHub Releases に移行) | 本来の utility としての upload |
| 配布チャネル | GitHub Releases | GitHub Releases |
| carve-out check | scripts/check-no-k35.sh | scripts/check-no-k35.sh (同じ pattern) |

## Compact / Session Identity

`CLAUDE.md` に `**THIS SESSION IS FOR: shot-sync**` を明記しているため、
Compact 後も他 PJ に流出しない (operating.md §7.4)。
