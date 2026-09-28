# SDK 테스트판·정식판 내는 법

**한 줄 요약** — 테스트판은 `release/버전` 브랜치를 만들어 Actions 에서 `prerelease` 워크플로를
**수동으로** 실행하면 나오고, 정식판은 그 브랜치를 `main` 에 머지한 뒤 `release` 워크플로를
**수동으로** 실행하면 나옵니다.

⚠️ iOS SDK 와 달리 **push 로 자동 실행되지 않습니다.** GitHub Actions 무료 한도(월 2,000분)를
지키려고 세 워크플로(`test` · `prerelease` · `release`) 모두 `workflow_dispatch:` 만 열어 뒀습니다
— Actions 탭에서 **Run workflow** 버튼을 눌러야 돕니다. 되살리려면(자동 실행이 필요해지면)
각 워크플로의 `on:` 에 `push:`/`pull_request:` 를 추가하세요.

| | 테스트판 | 정식판 |
|---|---|---|
| 이름 | `v0.0.2-rc.1`, `-rc.2` … | `v0.0.2` |
| 누가 받나 | 온보딩 앱 등 **우리 테스트 앱만** (고객 앱엔 절대 안 감) | **고객 앱** |
| 어떻게 나오나 | `release/0.0.2` 브랜치에서 `prerelease` 워크플로를 수동 실행할 때마다 | `main` 에서 `release` 워크플로를 수동 실행할 때 |
| 어디서 보나 | [Releases 페이지](https://github.com/onecheck-inc/OneS1ght-Android-SDK/releases) **Pre-release** 딱지 | 같은 페이지 **Latest** 딱지 |

---

## A. 테스트판 내기 (예: 0.0.2)

### 1. 브랜치 만들기
```bash
git fetch origin
git checkout -b release/0.0.2 origin/main
```
브랜치 이름은 반드시 `release/` + 버전. 이 이름의 숫자와 소스의 버전이 다르면 CI 가 멈춥니다.

### 2. 버전 올리기 — 파일 네 개
| 파일 | 고칠 곳 |
|---|---|
| `onesight/src/main/kotlin/co/onecheck/ones1ght/android/OneS1ght.kt` | `public const val SDK_VERSION: String = "0.0.2"` |
| `Snippets/android.json` | `"sdkVersion": "0.0.2"` |
| `Migrations/android.json` | `"currentVersion": "0.0.2"` 로 바꾸고, `migrations` 배열 **맨 끝**에 칸 하나 추가 ↓ |
| `CHANGELOG.md` | 맨 위에 `## [0.0.2] — 2026-10-01` 항목과 내용. 이 내용이 그대로 릴리스 노트가 됩니다 |

`Migrations/android.json` 에 추가할 칸 (앱 코드 고칠 게 없어도 넣습니다 — "최신으로 가는 길" 이 있어야 합니다):
```json
{
  "from": "0.0.1",
  "to": "0.0.2",
  "breaking": false,
  "summary": "무엇이 바뀌었는지 한두 문장.",
  "action": "앱 코드에서 할 일. 없으면: 의존성 해석만 다시 하면 된다. 코드는 고칠 것이 없다.",
  "changes": []
}
```

네 곳이 맞는지 푸시 전에 확인:
```bash
SKIP_TESTS=1 Scripts/check-release.sh
```
`0.0.2 내보낼 수 있음.` 이 뜨면 됩니다. ✗ 가 뜨면 그 줄이 말하는 파일을 고칩니다.
의존성·Kotlin 설정을 바꿨다면 `Scripts/consumer-compat-check.sh` 도 돌려 Java 8 · Kotlin 1.9/2.0 앱에서 컴파일되는지 확인합니다.

### 3. 푸시
```bash
git add -A
git commit -m "chore: 0.0.2"
git push -u origin release/0.0.2
```

### 4. `prerelease` 워크플로를 수동 실행
GitHub → **Actions** → `prerelease` → **Run workflow** → 브랜치로 `release/0.0.2` 선택 → 실행.
3분쯤 기다리면 초록이 되고 [Releases](https://github.com/onecheck-inc/OneS1ght-Android-SDK/releases)
에 **`v0.0.2-rc.1` (Pre-release)** 가 생깁니다. 빨강이면 → 아래 "CI 가 빨강일 때".

### 5. 테스트 앱에서 받기
배포 채널(Maven 저장소)이 아직 정해지지 않았습니다 — 테스트 앱에서 받는 절차는 채널이
정해지면 이 문서에 추가합니다. 지금은 태그(`v0.0.2-rc.1`)의 소스를 직접 참조하세요.

### 6. 고칠 게 나오면
```bash
git checkout -b fix/무엇 release/0.0.2
# 고치고 커밋
git push -u origin fix/무엇
```
GitHub 에서 PR — **base 를 `release/0.0.2`** 로. 머지된 뒤 `prerelease` 워크플로를 다시
수동 실행하면 `v0.0.2-rc.2` 가 나옵니다. 반복.

---

## B. 정식판 내기

### 1. PR 만들기
GitHub 에서 PR — base **`main`** ← compare **`release/0.0.2`**. `test` 워크플로를 수동
실행해 초록인지 확인(자동으로 돌지 않습니다 — A 의 안내와 같은 이유).

### 2. 머지

### 3. `release` 워크플로를 수동 실행
GitHub → **Actions** → `release` → **Run workflow** → 브랜치로 `main` 선택 → 실행.
3분쯤 기다리면 초록이 되고 Releases 에 **`v0.0.2` (Latest)** 가 생깁니다. 이 순간부터
고객 빌드가 이 판을 받습니다(배포 채널이 정해진 뒤).

### 4. 콘솔 값 올리기 (이건 손으로)
`console` 레포 `backend-spring/src/main/resources/sdk/codes-android.json` 의
`"minSdkVersion"` 을 `"0.0.2"` 로 → PR → 배포.
코딩 에이전트(MCP)가 이 값으로 새 판의 스니펫을 가리킵니다. 콘솔 배포는 서버 재시작이므로
시점은 조율해서.
(릴리스 노트 맨 아래에도 이 안내가 자동으로 적혀 있습니다.)

### 5. 브랜치 지우기
```bash
git push origin --delete release/0.0.2
```

---

## C. 급한 수정 (핫픽스)
똑같이 A → B 를 다음 번호(`release/0.0.3`)로. rc 한 번은 거칩니다.
정말 급하면 `main` 으로 바로 PR 을 내고 2번의 네 파일을 올려도 됩니다 — 단, 테스트판 없이
바로 고객에게 나갑니다.

---

## CI 가 빨강일 때

| 메시지 | 뜻 | 할 일 |
|---|---|---|
| `브랜치는 release/0.0.2 인데 소스 SDK_VERSION 은 0.0.1` | A-2 를 안 함 | 네 파일 올리고 다시 실행 |
| `Snippets/android.json 이 0.0.1` / `Migrations/android.json 이 0.0.1` | 그 파일만 안 올림 | 그 파일 고침 |
| `마이그레이션 마지막 칸의 to 가 0.0.1` | Migrations 에 칸 추가 안 함 | 칸 추가 |
| `CHANGELOG.md 에 [0.0.2] 항목이 없다` | CHANGELOG 안 씀 | 항목 추가 |
| `테스트 실패` | 테스트 깨짐 | 로컬에서 `./gradlew :onesight:testDebugUnitTest` |
| `v0.0.2 은 이미 정식 배포된 판입니다` | 이미 나간 번호에 rc 를 더 달려 함 | `release/0.0.3` 로 새로 |

## 규칙 (지키지 않으면 고객이 다칩니다)
- **태그는 절대 지우거나 옮기지 않습니다.** 잘못 나갔으면 다음 번호로 다시 냅니다.
- **고객 코드를 고쳐야 하는 변경이면 `1.0.0` 으로 올립니다.**
- `main` 에 문서·CI 만 고친 PR 을 머지해도 **아무 일도 일어나지 않습니다** — `release`
  워크플로를 수동으로 돌리지 않는 한 릴리스되지 않습니다(버전을 안 올렸다면 돌려도
  `이미 있다` 로 건너뜁니다).
- **워크플로는 전부 수동 실행입니다.** Actions 한도를 지키려는 결정이며, PR 을 올리거나
  머지하는 것만으로는 아무 CI 도 돌지 않습니다 — 확인이 필요하면 매번 Run workflow 를
  눌러야 합니다.

## CI 자체가 죽었을 때
`main` 에서 `Scripts/release.sh 0.0.2` — CI 가 하는 검사·태그·확인·Release 를 사람 손으로
똑같이 밟습니다.
