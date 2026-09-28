#!/usr/bin/env bash
#
# 릴리스 전 검사 — "이 트리를 <버전> 으로 내보낼 수 있는 상태인가" 만 본다.
# 태그·푸시는 하지 않는다. CI(test.yml · prerelease.yml · release.yml)와
# 비상용 Scripts/release.sh 가 같은 검사를 공유하려고 떼어 둔 것이다.
#
# 사용:  Scripts/check-release.sh [버전]      (생략 = 소스의 OneS1ght.SDK_VERSION)
#        SKIP_TESTS=1 Scripts/check-release.sh (테스트를 이미 돌린 단계에서)
#
# 검사
#   1. OneS1ght.SDK_VERSION · Snippets/android.json · Migrations/android.json 세 곳의 버전 일치
#   2. Migrations 마지막 칸의 to 가 이 버전 — 최신으로 가는 길이 있는가(빈 체인은 0.0.1 만 허용)
#   3. CHANGELOG.md 에 [버전] 항목
#   4. ./gradlew :onesight:testDebugUnitTest
#
set -euo pipefail
cd "$(dirname "$0")/.."

SRC_VERSION=$(Scripts/sdk-version.sh)
VERSION="${1:-$SRC_VERSION}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "버전 형식이 올바르지 않습니다: $VERSION (x.y.z)" >&2
    exit 1
fi
FAIL=0
bad() { printf '  ✗ %s\n' "$1" >&2; FAIL=1; }
ok()  { printf '  ✓ %s\n' "$1"; }

echo "▸ 버전 일치 확인 ($VERSION)"
SNIPPET_VERSION=$(python3 -c 'import json;print(json.load(open("Snippets/android.json"))["sdkVersion"])')
MIGRATION_VERSION=$(python3 -c 'import json;print(json.load(open("Migrations/android.json"))["currentVersion"])')
[[ "$SRC_VERSION" == "$VERSION" ]] || bad "OneS1ght.SDK_VERSION 이 $SRC_VERSION (기대: $VERSION)"
[[ "$SNIPPET_VERSION" == "$VERSION" ]] || bad "Snippets/android.json 이 $SNIPPET_VERSION"
[[ "$MIGRATION_VERSION" == "$VERSION" ]] || bad "Migrations/android.json 이 $MIGRATION_VERSION"
[[ $FAIL -eq 0 ]] && ok "세 곳 모두 $VERSION"

# 마이그레이션의 마지막 칸이 이 버전으로 끝나야 "최신으로 가는 길" 이 있다.
# 체인이 비어 있는 것은 0.0.1(첫 판, 올라올 이전 버전이 없다) 에서만 허용한다.
LAST_TO=$(python3 -c 'import json;m=json.load(open("Migrations/android.json"))["migrations"];print(m[-1]["to"] if m else "")')
if [[ "$LAST_TO" == "$VERSION" ]]; then ok "마이그레이션 마지막 칸 → $VERSION"
elif [[ -z "$LAST_TO" && "$VERSION" == "0.0.1" ]]; then ok "마이그레이션 빈 체인 (첫 판 0.0.1)"
else bad "마이그레이션 마지막 칸의 to 가 $LAST_TO — $VERSION 로 가는 경로가 없다"; fi

if grep -q "^## \[$VERSION\]" CHANGELOG.md; then ok "CHANGELOG [$VERSION]"
else bad "CHANGELOG.md 에 [$VERSION] 항목이 없다"; fi

if [[ "${SKIP_TESTS:-}" == "1" ]]; then
    echo "▸ 테스트 (건너뜀 — SKIP_TESTS=1)"
else
    echo "▸ 테스트"
    if ./gradlew :onesight:testDebugUnitTest >/dev/null 2>&1; then ok "테스트 통과"; else bad "테스트 실패"; fi
fi

if [[ $FAIL -ne 0 ]]; then
    echo >&2
    echo "$VERSION 로 내보낼 수 없습니다. 위 항목을 고치세요." >&2
    exit 1
fi
echo "$VERSION 내보낼 수 있음."
