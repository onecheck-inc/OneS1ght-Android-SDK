#!/usr/bin/env bash
#
# 고객 앱 호환 검사 — "오래된 Kotlin 앱과 Java 앱이 이 SDK 를 실제로 컴파일할 수 있는가".
#
# 1. :onesight:assembleDebug 로 AAR 을 만들고, :onesight:exportConsumerApiClasspath 로
#    고객 컴파일 클래스패스(SDK 의 api 의존과 그 전이 api 의존 — implementation 은 안 보인다)를 뽑는다.
# 2. build/consumer-compat/work 아래에 작은 소비자 코드 두 개를 만들어 컴파일한다.
#    (a) Kotlin — Kotlin 2.0.21·1.9.25 컴파일러(kotlinc), jvm-target 1.8. suspend 판을 부른다.
#    (b) Java   — javac --release 8. Callback 판을 부른다.
#    둘 다 classes.jar + android.jar(compileOnly) + api 의존만 클래스패스에 둔다.
#    initialize → identify → buildings → floors → setFloorMap → floorSession().begin → 리스너 → end,
#    requestPermission(activity), onStopped(StopReason)·onFloorDetected(Floor.id), 그리고 기본 구현 멤버를 오버라이드하지 않은 PositioningProvider 구현으로 begin(provider),
#    앱이 직접 만든 UwbPositioningProvider(상태 StateFlow·게터·훅·진단)로 begin(provider)(0.0.5~).
# 3. 하나라도 실패하면 0 이 아닌 값으로 끝난다.
#
# kotlinc 는 GitHub 릴리스 zip 을 받아 캐시에 푼다(전역 설치 없음, sha256 대조):
#   ${ONESIGHT_TOOL_CACHE:-~/.cache/onesight-sdk}/kotlinc-<버전>
#
# 배포본 모드(PUBLISHED=1) — 1 의 빌드 산출물 대신 "배포된 좌표" 를 받아서 같은 컴파일을 한다.
#    build/consumer-compat/published 에 안드로이드 플러그인 없는 일회용 Gradle 프로젝트를 만들어
#    com.ones1ght.sdk:android:<SDK_VERSION> 을 mavenLocal()(→ google() · mavenCentral()) 에서 받고,
#    고객 컴파일 클래스패스(AAR + 전이 api 의존)를 Gradle 메타데이터 그대로 푼다. 받기 전에 배포본이
#    엔진 저장소 좌표를 참조하지 않는지, AAR 에 엔진 jar(libs/) 가 들어 있는지도 본다.
#    먼저 ./gradlew :onesight:publishToMavenLocal 로 올려 둔다(RELEASING.md D).
#
# 사용:  Scripts/consumer-compat-check.sh
#        CONSUMER_KOTLIN_VERSIONS="2.1.21" Scripts/consumer-compat-check.sh   (검사할 Kotlin 버전 바꾸기)
#        SKIP_BUILD=1 Scripts/consumer-compat-check.sh   (AAR·클래스패스를 이미 만든 단계에서)
#        PUBLISHED=1 Scripts/consumer-compat-check.sh    (mavenLocal 의 배포본으로 검사)
#        PUBLISHED=1 CONSUMER_SDK_VERSION=0.0.2 Scripts/consumer-compat-check.sh   (받을 버전 지정)
#        MINIFIED=1 Scripts/consumer-compat-check.sh     (mavenLocal 배포본을 minifyEnabled 앱으로 빌드 —
#                                                        R8 뒤에도 엔진 응답 모델이 원래 이름으로 남는지)
#
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

# 2.0.21 = 목표(언어·API 2.0). 1.9.25 = README 의 "Kotlin 1.9+" 약속(1.9 컴파일러는 메타데이터 2.0 까지 읽는다).
KOTLIN_VERSIONS="${CONSUMER_KOTLIN_VERSIONS:-2.0.21 1.9.25}"
CACHE="${ONESIGHT_TOOL_CACHE:-$HOME/.cache/onesight-sdk}"
WORK="$ROOT/onesight/build/consumer-compat/work"
API_DEPS="$ROOT/onesight/build/consumer-compat/api-deps"
AAR="$ROOT/onesight/build/outputs/aar/onesight-debug.aar"

step() { printf '▸ %s\n' "$1"; }
ok()   { printf '  ✓ %s\n' "$1"; }
die()  { printf '  ✗ %s\n' "$1" >&2; exit 1; }

# --- 축소(R8) 모드 (MINIFIED=1) -------------------------------------------------------------------
# 고객 앱이 minifyEnabled 로 빌드해도 SDK·엔진이 살아남는가 — "컴파일된다" 와 별개의 질문이다.
# 엔진은 서버 응답을 Gson 으로 public 필드 POJO 에 바로 푼다. R8 이 그 필드 이름을 바꾸거나 지우면
# 컴파일·실행은 되는데 층·구역만 조용히 비어 버린다. 그래서 mavenLocal 배포본에 기대는 일회용 앱을
# minifyEnabled=true 로 실제 빌드하고, 출력 dex 에 그 클래스·필드가 원래 이름 그대로 있는지 대조한다.
# 먼저 ./gradlew :onesight:publishToMavenLocal.
if [[ "${MINIFIED:-}" == "1" ]]; then
    SDK_VERSION="${CONSUMER_SDK_VERSION:-$(Scripts/sdk-version.sh)}"
    COORD="com.ones1ght.sdk:android:$SDK_VERSION"
    LOCAL_DIR="$HOME/.m2/repository/com/ones1ght/sdk/android/$SDK_VERSION"
    [[ -f "$LOCAL_DIR/android-$SDK_VERSION.aar" ]] || die "mavenLocal 에 $COORD 가 없다 — 먼저 ./gradlew :onesight:publishToMavenLocal"
    SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z "$SDK_DIR" && -f local.properties ]]; then SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1); fi
    [[ -d "$SDK_DIR" ]] || die "Android SDK 를 찾지 못함 — ANDROID_HOME 또는 local.properties sdk.dir"
    DEXDUMP=$(ls -d "$SDK_DIR"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1)
    [[ -x "$DEXDUMP" ]] || die "build-tools 의 dexdump 를 찾지 못함"
    AGP=$(sed -n 's/^agp = "\(.*\)"/\1/p' gradle/libs.versions.toml)
    COMPILE_SDK=$(sed -n 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' onesight/build.gradle.kts | head -1)
    MIN="$ROOT/onesight/build/consumer-compat/minified"

    step "minifyEnabled 앱으로 $COORD 빌드 (AGP $AGP · R8)"
    rm -rf "$MIN"; mkdir -p "$MIN/src/main/java/consumer"
    echo "sdk.dir=$SDK_DIR" > "$MIN/local.properties"
    printf 'android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2048m\n' > "$MIN/gradle.properties"
    cat > "$MIN/settings.gradle.kts" <<KTS
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("com.ones1ght.sdk") } }
        google()
        mavenCentral()
    }
}
rootProject.name = "onesight-minified-consumer"
KTS
    cat > "$MIN/build.gradle.kts" <<KTS
plugins { id("com.android.application") version "$AGP" }
android {
    namespace = "consumer.minified"
    compileSdk = $COMPILE_SDK
    defaultConfig { applicationId = "consumer.minified"; minSdk = 26; targetSdk = $COMPILE_SDK }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("$COORD") }
KTS
    cat > "$MIN/src/main/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name="consumer.App" />
</manifest>
XML
    cat > "$MIN/src/main/java/consumer/App.java" <<'JAVA'
package consumer;

import android.app.Application;
import co.onecheck.ones1ght.android.Callback;
import co.onecheck.ones1ght.android.OneS1ght;

/** 고객 앱처럼 SDK 공개 API 만 부른다 — 엔진은 SDK 안쪽에서만 쓰인다. */
public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        OneS1ght.initialize(this, "ock_minified", new Callback<Void>() {
            @Override public void onSuccess(Void r) {
                OneS1ght.identify("pf_minified");
                OneS1ght.floorSession().begin(new Callback<Void>() {
                    @Override public void onSuccess(Void r2) {}
                    @Override public void onError(Throwable e) {}
                });
            }
            @Override public void onError(Throwable e) {}
        });
    }
}
JAVA
    ./gradlew -q -p "$MIN" assembleRelease > "$MIN/build.log" 2>&1 || { tail -40 "$MIN/build.log" >&2; die "minifyEnabled 앱 빌드 실패(R8)"; }
    APK=$(ls "$MIN"/build/outputs/apk/release/*.apk | head -1)
    [[ -f "$APK" ]] || die "APK 없음"
    ok "R8 빌드 통과 — $(basename "$APK")"

    step "R8 출력 dex 에서 엔진 응답 모델(Gson)·진입점이 원래 이름으로 남았는지"
    rm -rf "$MIN/dex" "$MIN/engine"; mkdir -p "$MIN/dex" "$MIN/engine"
    unzip -q -o "$APK" 'classes*.dex' -d "$MIN/dex"
    for d in "$MIN"/dex/*.dex; do "$DEXDUMP" -l plain "$d"; done > "$MIN/dexdump.txt" 2>/dev/null
    # 기대값 = 배포본 AAR 에 실린 엔진 jar 의 실제 선언(필드 이름)
    unzip -q -o "$LOCAL_DIR/android-$SDK_VERSION.aar" 'libs/*.jar' -d "$MIN/engine"
    for j in "$MIN"/engine/libs/*.jar; do unzip -q -o "$j" -d "$MIN/engine/classes"; done
    python3 - "$MIN/dexdump.txt" "$MIN/engine/classes" "$MIN/build/outputs/mapping/release/mapping.txt" <<'PY' || die "R8 가 엔진 응답 모델을 지웠거나 이름을 바꿨다 — consumer-rules.pro 확인"
import pathlib, re, subprocess, sys
dump, root = pathlib.Path(sys.argv[1]).read_text(), pathlib.Path(sys.argv[2])
# dexdump: 클래스 descriptor → 필드 이름 집합
dex = {}
cur = None
for line in dump.splitlines():
    m = re.match(r"\s*Class descriptor\s*:\s*'L(.+);'", line)
    if m:
        cur = m.group(1); dex[cur] = set(); continue
    m = re.match(r"\s*name\s*:\s*'(.+)'", line)
    if m and cur is not None:
        dex[cur].add(m.group(1))
targets = sorted(p for p in root.rglob("*.class")
                 if re.search(r"ihub/internal/(floor/FloorInfra|webapi/Geofences)(\$|\.class)", str(p)))
targets += [root / "kr/geoplan/android/lib/ihub/IntelligenceHub.class"]
if len(targets) < 3:
    print("엔진 응답 모델 클래스를 못 찾음", file=sys.stderr); sys.exit(1)
bad = 0
for p in targets:
    name = str(p.relative_to(root))[:-len(".class")]
    out = subprocess.run(["javap", "-p", str(p)], capture_output=True, text=True).stdout
    fields = {re.sub(r"[;\s]+$", "", l).split()[-1] for l in out.splitlines()[1:]
              if l.startswith("  ") and "(" not in l and l.strip() not in ("}", "static {};")}
    have = dex.get(name)
    if have is None:
        print(f"  ✗ {name} — dex 에 없음(지워졌거나 이름이 바뀜)", file=sys.stderr); bad += 1; continue
    missing = sorted(fields - have)
    if missing:
        print(f"  ✗ {name} — 필드 {missing} 없음/이름 바뀜", file=sys.stderr); bad += 1; continue
    print(f"  ✓ {name.split('/')[-1]} — 필드 {len(fields)}개 원래 이름")
# SDK 자신은 고객 앱 R8 이 이름을 줄여도 된다(공개 API 를 앱이 부르므로 살아남는다) — 매핑에 있으면 된다.
mapping = pathlib.Path(sys.argv[3]).read_text()
if not re.search(r"^co\.onecheck\.ones1ght\.android\.OneS1ght -> ", mapping, re.M):
    print("  ✗ SDK 진입점(OneS1ght)이 R8 출력에 없다", file=sys.stderr); bad += 1
else:
    print("  ✓ SDK 진입점 OneS1ght 포함")
sys.exit(1 if bad else 0)
PY
    echo "축소(R8) 호환 검사 통과 — 배포본 $COORD."
    exit 0
fi

# --- 1. 빌드 ---------------------------------------------------------------------------------
if [[ "${PUBLISHED:-}" == "1" ]]; then
    SDK_VERSION="${CONSUMER_SDK_VERSION:-$(Scripts/sdk-version.sh)}"
    COORD="com.ones1ght.sdk:android:$SDK_VERSION"
    PUB="$ROOT/onesight/build/consumer-compat/published"
    LOCAL_DIR="$HOME/.m2/repository/com/ones1ght/sdk/android/$SDK_VERSION"
    step "배포본 $COORD (mavenLocal) 확인"
    [[ -f "$LOCAL_DIR/android-$SDK_VERSION.module" && -f "$LOCAL_DIR/android-$SDK_VERSION.pom" ]] \
        || die "mavenLocal 에 $COORD 가 없다 — 먼저 ./gradlew :onesight:publishToMavenLocal"
    # 엔진 저장소 그룹(settings.gradle.kts 의 includeGroup)이 POM·.module 에 새면 고객 빌드가 깨진다.
    ENGINE_GROUP=$(sed -n 's/.*includeGroup("\([^"]*\)").*/\1/p' settings.gradle.kts | head -1)
    [[ -n "$ENGINE_GROUP" ]] || die "settings.gradle.kts 에서 엔진 그룹을 찾지 못함"
    if grep -qF "$ENGINE_GROUP" "$LOCAL_DIR/android-$SDK_VERSION.pom" "$LOCAL_DIR/android-$SDK_VERSION.module"; then
        die "배포본 POM/.module 이 엔진 저장소 좌표를 참조한다"
    fi
    ok "POM·.module 에 엔진 저장소 좌표 없음"
    ENGINE_JARS=$(unzip -Z1 "$LOCAL_DIR/android-$SDK_VERSION.aar" | grep -c '^libs/.*\.jar$' || true)
    [[ "$ENGINE_JARS" -ge 1 ]] || die "배포본 AAR 에 libs/*.jar(엔진) 가 없다 — 스텁 빌드가 올라갔다"
    ok "AAR 에 엔진 jar ${ENGINE_JARS}개"

    step "일회용 Gradle 프로젝트로 $COORD 받기"
    rm -rf "$PUB"; mkdir -p "$PUB"
    cat > "$PUB/settings.gradle.kts" <<'KTS'
rootProject.name = "onesight-published-consumer"
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("com.ones1ght.sdk") } }
        google()
        mavenCentral()
    }
}
KTS
    cat > "$PUB/build.gradle.kts" <<KTS
// 안드로이드 앱이 이 SDK 를 받을 때 보는 컴파일 클래스패스를 흉내 낸다 — java-api 변형 + AGP 처럼
// 런타임과 같은 버전으로 맞춤. AGP 없이 속성만으로 고른다(java-base = JVM 속성 호환 규칙: android 소비자도
// standard-jvm 변형을 받는다).
plugins { \`java-base\` }
val kotlinPlatform = Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)
// Kotlin Gradle 플러그인이 해 주던 규칙 — androidJvm 소비자는 jvm 변형도 받고, 둘 다 있으면 androidJvm 을 고른다.
class AndroidJvmAcceptsJvm : AttributeCompatibilityRule<String> {
    override fun execute(d: CompatibilityCheckDetails<String>) {
        if (d.consumerValue == "androidJvm" && d.producerValue == "jvm") d.compatible()
    }
}
class PreferAndroidJvm : AttributeDisambiguationRule<String> {
    override fun execute(d: MultipleCandidatesDetails<String>) {
        if ("androidJvm" in d.candidateValues) d.closestMatch("androidJvm")
        else if ("jvm" in d.candidateValues) d.closestMatch("jvm")
    }
}
dependencies.attributesSchema.attribute(kotlinPlatform) {
    compatibilityRules.add(AndroidJvmAcceptsJvm::class.java)
    disambiguationRules.add(PreferAndroidJvm::class.java)
}
fun Configuration.androidLike(usage: String) {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(usage))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.ANDROID))
        attribute(kotlinPlatform, "androidJvm")
    }
}
val sdkRuntime = configurations.create("sdkRuntime") { androidLike(Usage.JAVA_RUNTIME) }
val sdkCompile = configurations.create("sdkCompile") {
    androidLike(Usage.JAVA_API)
    shouldResolveConsistentlyWith(sdkRuntime)
}
dependencies {
    add("sdkRuntime", "$COORD")
    add("sdkCompile", "$COORD")
}
tasks.register<Sync>("exportSdkCompile") {
    from(sdkCompile)
    into(layout.buildDirectory.dir("api-deps"))
}
tasks.register("printSdkRuntime") {
    val ids = sdkRuntime.incoming.resolutionResult.rootComponent.map { root ->
        root.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .flatMap { d -> listOf(d.selected) + d.selected.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().map { it.selected } }
            .map { it.moduleVersion.toString() }.distinct().sorted()
    }
    doLast { ids.get().forEach { println("runtime: \$it") } }
}
KTS
    ./gradlew -q -p "$PUB" exportSdkCompile printSdkRuntime > "$PUB/resolve.log" 2>&1 \
        || { cat "$PUB/resolve.log" >&2; die "$COORD 받기 실패"; }
    grep '^runtime: ' "$PUB/resolve.log" | sed 's/^runtime: /    · /' | grep -v 'com.ones1ght.sdk:android' | head -20
    # api-deps 에 SDK 자신(AAR)도 함께 풀린다 — AAR 은 따로 두고 나머지만 api 의존으로 쓴다.
    API_DEPS="$PUB/api-deps"
    rm -rf "$API_DEPS"; mkdir -p "$API_DEPS"
    for f in "$PUB/build/api-deps"/*; do
        case "$(basename "$f")" in
            android-"$SDK_VERSION".aar) AAR="$PUB/onesight-published.aar"; cp "$f" "$AAR" ;;
            *) cp "$f" "$API_DEPS/" ;;
        esac
    done
    [[ "$AAR" == "$PUB/onesight-published.aar" ]] || die "받은 클래스패스에 $COORD AAR 이 없다"
    WORK="$PUB/work"
elif [[ "${SKIP_BUILD:-}" == "1" ]]; then
    step "빌드 (건너뜀 — SKIP_BUILD=1)"
else
    step "AAR·고객 컴파일 클래스패스 만들기"
    ./gradlew -q :onesight:assembleDebug :onesight:exportConsumerApiClasspath >/dev/null || die "gradle 빌드 실패"
fi
[[ -f "$AAR" ]] || die "AAR 없음: $AAR"
[[ -d "$API_DEPS" ]] || die "api 의존 없음: $API_DEPS"
ok "$(basename "$AAR") · api 의존 $(ls "$API_DEPS" | wc -l | tr -d ' ')개"

# --- android.jar -----------------------------------------------------------------------------
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f local.properties ]]; then
    SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)
fi
COMPILE_SDK=$(sed -n 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' onesight/build.gradle.kts | head -1)
ANDROID_JAR=""
for d in "$SDK_DIR/platforms/android-$COMPILE_SDK" "$SDK_DIR"/platforms/android-"$COMPILE_SDK".*; do
    [[ -f "$d/android.jar" ]] && { ANDROID_JAR="$d/android.jar"; break; }
done
[[ -n "$ANDROID_JAR" ]] || die "android.jar(android-$COMPILE_SDK) 를 찾지 못함 — ANDROID_HOME 또는 local.properties sdk.dir"

# --- kotlinc (캐시) ---------------------------------------------------------------------------
kotlinc_home() {   # $1 = 버전. 없으면 받아서 풀고 경로를 출력한다.
    local v="$1" home="$CACHE/kotlinc-$1"
    if [[ ! -x "$home/bin/kotlinc" ]]; then
        step "kotlinc $v 받기 → $home" >&2
        mkdir -p "$CACHE"
        local tmp url want got
        tmp=$(mktemp -d "$CACHE/dl.XXXXXX")
        url="https://github.com/JetBrains/kotlin/releases/download/v$v/kotlin-compiler-$v.zip"
        curl -fsSL -o "$tmp/k.zip" "$url" || { rm -rf "$tmp"; die "다운로드 실패: $url"; }
        want=$(curl -fsSL "$url.sha256" | awk '{print $1}') || { rm -rf "$tmp"; die "sha256 받기 실패"; }
        got=$(shasum -a 256 "$tmp/k.zip" | awk '{print $1}')
        [[ -n "$want" && "$want" == "$got" ]] || { rm -rf "$tmp"; die "kotlinc $v zip sha256 불일치 ($got ≠ $want)"; }
        unzip -q "$tmp/k.zip" -d "$tmp/x"
        rm -rf "$home"
        mv "$tmp/x/kotlinc" "$home"
        rm -rf "$tmp"
    fi
    echo "$home"
}

# --- 클래스패스 조립 --------------------------------------------------------------------------
rm -rf "$WORK"
mkdir -p "$WORK/jars" "$WORK/kotlin/src" "$WORK/kotlin/out" "$WORK/java/src" "$WORK/java/out"
unzip -q -p "$AAR" classes.jar > "$WORK/jars/onesight.jar"
for f in "$API_DEPS"/*; do
    case "$f" in
        *.aar) unzip -q -p "$f" classes.jar > "$WORK/jars/$(basename "$f" .aar).jar" 2>/dev/null \
                   || rm -f "$WORK/jars/$(basename "$f" .aar).jar" ;;   # 클래스 없는 AAR(리소스만)
        *.jar) cp "$f" "$WORK/jars/" ;;
    esac
done
CP="$ANDROID_JAR"
for j in "$WORK"/jars/*.jar; do CP="$CP:$j"; done

# --- (a) Kotlin 소비자 ------------------------------------------------------------------------
cat > "$WORK/kotlin/src/KotlinConsumer.kt" <<'KT'
package consumer

import android.content.Context
import androidx.activity.ComponentActivity
import co.onecheck.ones1ght.android.ConfigChangeListener
import co.onecheck.ones1ght.android.DebugLogListener
import co.onecheck.ones1ght.android.DwellListener
import co.onecheck.ones1ght.android.OneS1ght
import co.onecheck.ones1ght.android.PermissionStatus
import co.onecheck.ones1ght.android.PositionListener
import co.onecheck.ones1ght.android.SdkError
import co.onecheck.ones1ght.android.FloorSession
import co.onecheck.ones1ght.android.SessionFloorListener
import co.onecheck.ones1ght.android.SessionStoppedListener
import co.onecheck.ones1ght.android.TriggersListener
import co.onecheck.ones1ght.android.ZoneListener
import co.onecheck.ones1ght.android.model.Building
import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.positioning.EngineErrorListener
import co.onecheck.ones1ght.android.positioning.FloorDetectedListener
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProviderDelegate
import co.onecheck.ones1ght.android.positioning.ProviderChangeListener
import co.onecheck.ones1ght.android.positioning.RawAreaEventListener
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider
import co.onecheck.ones1ght.android.positioning.ZoneEventListener
import co.onecheck.ones1ght.android.runtime.LogLevel
import kotlinx.coroutines.flow.StateFlow

/** 필수 멤버만 구현 — 기본 구현이 있는 pause·resume·apply 등은 오버라이드하지 않는다. */
class KotlinProvider : PositioningProvider {
    override var delegate: PositioningProviderDelegate? = null
    override fun start() {}
    override fun stop() {}
}

/** 권한 요청 — suspend 판(androidx.activity 의 ComponentActivity 를 받는다: api 의존이 고객 클래스패스에 있는지). */
suspend fun kotlinPermissions(activity: ComponentActivity): Boolean =
    OneS1ght.requestPermission(activity) == PermissionStatus.AUTHORIZED

/** Kotlin 앱이 쓰는 모양 그대로 — suspend 판 + fun interface SAM 변환. */
suspend fun kotlinFlow(context: Context): String {
    OneS1ght.initialize(context, "ock_consumer")
    OneS1ght.identify("pf_consumer")
    val buildings: List<Building> = OneS1ght.buildings()
    val floors: List<Floor> = OneS1ght.floors(buildings[0].id)
    OneS1ght.setFloorMap(floors[0], buildingId = buildings[0].id)
    OneS1ght.setFloorMap(floors[0])
    OneS1ght.onDebugLog = DebugLogListener { level, message -> println("$level $message") }

    val session = OneS1ght.floorSession()
    session.onZoneEnter = ZoneListener { zone -> println(zone.id) }
    session.onZoneExit = ZoneListener { zone -> println(zone.name) }
    session.onZoneDwell = DwellListener { zone, seconds -> println("${zone.id} $seconds") }
    session.onPosition = PositionListener { c -> println("${c.x},${c.y},${c.z}") }
    session.onTriggers = TriggersListener { zoneId, triggers -> println("$zoneId ${triggers.size}") }
    session.onConfigChanged = ConfigChangeListener { change -> println(change) }
    session.onStopped = SessionStoppedListener { reason ->
        when (reason) {
            FloorSession.StopReason.ENGINE_FAILED -> println("retry")
            else -> Unit
        }
    }
    session.onFloorDetected = SessionFloorListener { floorId: String? -> println(floorId) }
    OneS1ght.uploadPendingPositions()
    OneS1ght.discardPendingPositions()
    println(OneS1ght.DEFAULT_BASE_URL)
    try {
        session.begin()
    } catch (e: SdkError) {
        return e.code.code
    }
    val running: Boolean = session.isRunning
    session.end()
    val custom = KotlinProvider()
    custom.pause(); custom.resume()
    session.begin(custom)
    session.end()
    return "running=$running version=${OneS1ght.SDK_VERSION} ${kotlinUwbProvider(context)}"
}

/** 앱이 직접 만든 측위 provider — 상태 흐름·게터·훅·진단(0.0.5~). */
suspend fun kotlinUwbProvider(context: Context): String {
    if (!UwbPositioningProvider.isSupported(context)) return "unsupported"
    val provider = UwbPositioningProvider(context)
    provider.onFloorDetected = FloorDetectedListener { floorId -> println(floorId) }
    provider.onEngineError = EngineErrorListener { code, message -> println("$code $message") }
    provider.onRawAreaEvent = RawAreaEventListener { floorId, name, inOut, atMs -> println("$floorId $name $inOut $atMs") }
    provider.onZoneEvent = ZoneEventListener { event -> println(event.label) }
    provider.onLog = DebugLogListener { level, message -> println("$level $message") }
    provider.onChange = ProviderChangeListener { p -> println(p.phase) }
    val phase: StateFlow<UwbPositioningProvider.PositioningPhase> = provider.phaseFlow
    val position = provider.latestPositionFlow.value
    provider.note(LogLevel.INFO, "consumer")
    provider.startDetection()
    OneS1ght.floorSession().begin(provider)
    val d = provider.diagnostic
    val line = "${phase.value} ${provider.isDetecting} ${provider.isRunning} ${provider.isPaused} $position " +
        "${provider.detectedFloorId} ${provider.measurementCount} ${provider.log.size} ${d.registered} ${d.hasFix} ${d.summary}"
    OneS1ght.floorSession().end()
    provider.stopDetection()
    return line
}
KT

for v in $KOTLIN_VERSIONS; do
    home=$(kotlinc_home "$v")
    step "Kotlin $v 소비자 컴파일 (jvm-target 1.8)"
    out="$WORK/kotlin/out-$v"; mkdir -p "$out"
    # 언어·API 수준은 지정하지 않는다 — 그 컴파일러의 기본값(= 그 버전의 앱) 그대로 본다.
    "$home/bin/kotlinc" -jvm-target 1.8 -Werror -no-reflect \
        -classpath "$CP" -d "$out" "$WORK/kotlin/src/KotlinConsumer.kt" \
        > "$WORK/kotlin/log-$v.txt" 2>&1 || { cat "$WORK/kotlin/log-$v.txt" >&2; die "Kotlin $v 소비자 컴파일 실패"; }
    ok "Kotlin $("$home/bin/kotlinc" -version 2>&1 | sed -n 's/.*kotlinc-jvm \([^ ]*\).*/\1/p') 컴파일 통과"
done

# --- (b) Java 소비자 --------------------------------------------------------------------------
mkdir -p "$WORK/java/src/consumer"
cat > "$WORK/java/src/consumer/JavaConsumer.java" <<'JAVA'
package consumer;

import android.content.Context;
import androidx.activity.ComponentActivity;
import co.onecheck.ones1ght.android.Callback;
import co.onecheck.ones1ght.android.FloorSession;
import co.onecheck.ones1ght.android.OneS1ght;
import co.onecheck.ones1ght.android.PermissionStatus;
import co.onecheck.ones1ght.android.SdkError;
import co.onecheck.ones1ght.android.model.Building;
import co.onecheck.ones1ght.android.model.Floor;
import co.onecheck.ones1ght.android.positioning.PositioningProvider;
import co.onecheck.ones1ght.android.positioning.PositioningProviderDelegate;
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider;
import co.onecheck.ones1ght.android.runtime.LogLevel;
import java.util.List;

/** Java 8 앱이 쓰는 모양 그대로 — 정적 호출 + Callback 판 + 람다 리스너. Kotlin 타입(Unit·Function·Continuation)을 쓰지 않는다. */
public final class JavaConsumer {
    private JavaConsumer() {}

    /** 필수 멤버만 구현 — 기본 구현(pause·resume·apply 등)은 JVM default 메서드라 오버라이드하지 않아도 된다. */
    static final class JavaProvider implements PositioningProvider {
        private PositioningProviderDelegate delegate;
        @Override public PositioningProviderDelegate getDelegate() { return delegate; }
        @Override public void setDelegate(PositioningProviderDelegate value) { delegate = value; }
        @Override public void start() {}
        @Override public void stop() {}
    }

    /** 권한 요청 — Callback 판. */
    public static void javaPermissions(ComponentActivity activity) {
        OneS1ght.requestPermission(activity, new Callback<PermissionStatus>() {
            @Override public void onSuccess(PermissionStatus status) { System.out.println(status == PermissionStatus.AUTHORIZED); }
            @Override public void onError(Throwable e) {}
        });
    }

    public static void javaFlow(final Context context) {
        OneS1ght.initialize(context, "ock_consumer", new Callback<Void>() {
            @Override public void onSuccess(Void r) { afterInit(); }
            @Override public void onError(Throwable e) {
                if (e instanceof SdkError) System.out.println(((SdkError) e).getCode().getCode());
            }
        });
    }

    private static void afterInit() {
        OneS1ght.identify("pf_consumer");
        OneS1ght.setOnDebugLog((level, message) -> System.out.println(level + " " + message));
        OneS1ght.buildings(new Callback<List<Building>>() {
            @Override public void onSuccess(final List<Building> buildings) {
                final String buildingId = buildings.get(0).getId();
                OneS1ght.floors(buildingId, new Callback<List<Floor>>() {
                    @Override public void onSuccess(List<Floor> floors) {
                        OneS1ght.setFloorMap(floors.get(0), buildingId, new Callback<Void>() {
                            @Override public void onSuccess(Void r) { startSession(); }
                            @Override public void onError(Throwable e) {}
                        });
                        OneS1ght.setFloorMap(floors.get(0), new Callback<Void>() {
                            @Override public void onSuccess(Void r) {}
                            @Override public void onError(Throwable e) {}
                        });
                    }
                    @Override public void onError(Throwable e) {}
                });
            }
            @Override public void onError(Throwable e) {}
        });
    }

    private static void startSession() {
        final FloorSession session = OneS1ght.floorSession();
        session.setOnZoneEnter(zone -> System.out.println(zone.getId()));
        session.setOnZoneExit(zone -> System.out.println(zone.getName()));
        session.setOnZoneDwell((zone, seconds) -> System.out.println(zone.getId() + " " + seconds));
        session.setOnStopped(reason -> System.out.println(reason == FloorSession.StopReason.ENGINE_FAILED));
        session.setOnFloorDetected(floorId -> System.out.println(floorId));
        OneS1ght.discardPendingPositions();
        session.setOnPosition(c -> System.out.println(c.getX() + "," + c.getY() + "," + c.getZ()));
        session.setOnTriggers((zoneId, triggers) -> System.out.println(zoneId + " " + triggers.size()));
        session.setOnConfigChanged(change -> System.out.println(change));
        session.begin(new Callback<Void>() {
            @Override public void onSuccess(Void r) {
                boolean running = session.isRunning();
                session.end(new Callback<Void>() {
                    @Override public void onSuccess(Void r2) { System.out.println(running + " " + OneS1ght.SDK_VERSION); }
                    @Override public void onError(Throwable e) {}
                });
                JavaProvider custom = new JavaProvider();
                custom.pause();
                custom.resume();
                session.begin(custom, new Callback<Void>() {
                    @Override public void onSuccess(Void r3) {}
                    @Override public void onError(Throwable e) {}
                });
            }
            @Override public void onError(Throwable e) {}
        });
    }

    /** 앱이 직접 만든 측위 provider — 게터·훅·진단 + Callback 판 begin(provider)(0.0.5~). */
    public static void javaUwbProvider(Context context) {
        if (!UwbPositioningProvider.isSupported(context)) return;
        final UwbPositioningProvider provider = new UwbPositioningProvider(context);
        provider.setOnFloorDetected(floorId -> System.out.println(floorId));
        provider.setOnEngineError((code, message) -> System.out.println(code + " " + message));
        provider.setOnRawAreaEvent((floorId, name, inOut, atMs) -> System.out.println(floorId + name + inOut + atMs));
        provider.setOnZoneEvent(event -> System.out.println(event.getLabel()));
        provider.setOnLog((level, message) -> System.out.println(level + " " + message));
        provider.setOnChange(p -> System.out.println(p.getPhase()));
        provider.note(LogLevel.INFO, "consumer");
        provider.startDetection();
        OneS1ght.floorSession().begin(provider, new Callback<Void>() {
            @Override public void onSuccess(Void r) {
                UwbPositioningProvider.AnchorDiagnostic d = provider.getDiagnostic();
                System.out.println(provider.getPhase() + " " + provider.isDetecting() + " " + provider.isRunning() + " "
                    + provider.isPaused() + " " + provider.getLatestPosition() + " " + provider.getDetectedFloorId() + " "
                    + provider.getMeasurementCount() + " " + provider.getLog().size() + " " + d.getRegistered() + " "
                    + d.getHasFix() + " " + d.getSummary());
                provider.stopDetection();
            }
            @Override public void onError(Throwable e) {}
        });
    }
}
JAVA

step "Java 소비자 컴파일 (javac --release 8)"
# -Xlint:-options: --release 8 의 "곧 제거" 경고만 끈다. 그 외 경고는 -Werror 로 실패.
javac --release 8 -Xlint:all -Xlint:-options -Xlint:-classfile -Werror \
    -classpath "$CP" -d "$WORK/java/out" "$WORK/java/src/consumer/JavaConsumer.java" \
    > "$WORK/java/log.txt" 2>&1 || { cat "$WORK/java/log.txt" >&2; die "Java 소비자 컴파일 실패"; }
ok "javac $(javac -version 2>&1 | awk '{print $2}') --release 8 컴파일 통과"

echo "고객 앱 호환 검사 통과 (Kotlin ${KOTLIN_VERSIONS// / · } · Java 8)${COORD:+ — 배포본 $COORD}."
