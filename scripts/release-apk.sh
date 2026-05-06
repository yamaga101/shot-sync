#!/usr/bin/env bash
#
# release-apk.sh — debug APK を GitHub Releases にアップロード。
#
# ev-manager 同等の配布フロー (carve-out 例外、Drive 経由は使わない)。
# 安定 URL: https://github.com/yamaga101/shot-sync/releases/latest/download/app-debug.apk
#
# Usage:
#   bash scripts/release-apk.sh         # tag = v<package.json.version>, latest
#   bash scripts/release-apk.sh --prerelease   # 公開しない / latest にしない
#
# Requirements:
#   - gh CLI が yamaga101 で認証済
#   - APK ビルド済 (./gradlew :app:assembleDebug)
#

set -euo pipefail

REPO="yamaga101/shot-sync"
APK="app/build/outputs/apk/debug/app-debug.apk"
PRERELEASE_FLAG="--latest"

for arg in "$@"; do
  case "$arg" in
    --prerelease) PRERELEASE_FLAG="--prerelease" ;;
    --apk=*) APK="${arg#--apk=}" ;;
    *) echo "[error] unknown arg: $arg" >&2; exit 1 ;;
  esac
done

if [[ ! -f "$APK" ]]; then
  echo "[error] APK 無し: $APK" >&2
  echo "  build してから実行してください: npm run build" >&2
  exit 1
fi

VERSION=$(node -p "require('./package.json').version")
TAG="v${VERSION}"
NAME="shot-sync v${VERSION} (Android debug)"
ASSET_NAME="shot-sync-v${VERSION}-debug.apk"
SIZE_MB=$(awk "BEGIN {printf \"%.1f\", $(stat -f%z "$APK") / 1024 / 1024}")

echo "[info] repo    = ${REPO}"
echo "[info] version = ${VERSION}"
echo "[info] tag     = ${TAG}"
echo "[info] apk     = ${APK} (${SIZE_MB} MB)"

if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  echo "[info] release ${TAG} 既存 → asset を上書き"
  gh release upload "$TAG" "$APK#${ASSET_NAME}" --clobber -R "$REPO"
else
  echo "[info] release ${TAG} 新規作成"
  NOTES="shot-sync v${VERSION} Android debug APK

⚠️ 個人 carve-out ビルド (yamaga101)。debug 署名のため Play 配布不可。
端末で「不明な提供元のアプリのインストール」を許可してから install してください。"
  gh release create "$TAG" "$APK#${ASSET_NAME}" \
    --title "$NAME" \
    --notes "$NOTES" \
    $PRERELEASE_FLAG \
    -R "$REPO"
fi

URL=$(gh release view "$TAG" --json url -R "$REPO" -q .url)
ASSET_URL=$(gh release view "$TAG" --json assets -R "$REPO" -q ".assets[0].url")
LATEST_URL="https://github.com/${REPO}/releases/latest/download/app-debug.apk"

echo ""
echo "[ok]   release page : ${URL}"
echo "[ok]   asset url    : ${ASSET_URL}"
echo "[ok]   latest url   : ${LATEST_URL}   (← 安定: 次回 release でも同じ)"

if command -v pbcopy >/dev/null 2>&1; then
  printf '%s' "$LATEST_URL" | pbcopy
  echo "[ok]   pbcopy       : latest URL をクリップボードに copy 済"
fi
