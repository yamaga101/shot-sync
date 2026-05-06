#!/usr/bin/env bash
# Carve-out (yamaga101) 例外: shot-sync 内のコード/パス/トークンに k35 を一切混入しない。
# 違反は絶対バグ扱い。コメント文中の歴史的記録は許容。
#
# pre-commit hook + CI から呼ばれる。違反検知で exit 1。
#
# 検査対象:
#   - shigaki@k35.jp
#   - GoogleDrive-shigaki@k35
#   - その他 k35 ドメインに紐づく ID パターン
#
# 検査除外:
#   - .git/
#   - build/ build artifact
#   - docs/specs/system-yamaga101-carveout.md (説明文中で k35 を引用するため)
#   - scripts/check-no-k35.sh (この script 自体)
#   - CLAUDE.md の Quick Reference / README.md
#     (k35 を「混入禁止」と書く必要があるので説明箇所は許容)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 禁止パターン
PATTERNS=(
    'shigaki@k35\.jp'
    'GoogleDrive-shigaki@k35'
)

EXCLUDES=(
    --exclude-dir=.git
    --exclude-dir=build
    --exclude-dir=node_modules
    --exclude-dir=.gradle
    --exclude-dir=.claude
    --exclude=check-no-k35.sh
    --exclude=system-yamaga101-carveout.md
    --exclude=README.md
    --exclude=CLAUDE.md
)

violations=0
for pat in "${PATTERNS[@]}"; do
    if grep -rn -E "$pat" "${EXCLUDES[@]}" . 2>/dev/null; then
        violations=$((violations + 1))
    fi
done

if [[ $violations -gt 0 ]]; then
    echo ""
    echo "[error] 上記箇所に k35 混入を検出しました (${violations} pattern hit)" >&2
    echo "        carve-out 例外規約 違反です。修正してから commit してください。" >&2
    exit 1
fi

echo "[ok] k35 混入なし"
