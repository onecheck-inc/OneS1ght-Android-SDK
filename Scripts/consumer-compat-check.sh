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
#    permissions(activity), 그리고 기본 구현 멤버를 오버라이드하지 않은 PositioningProvider 구현으로 begin(provider).
# 3. 하나라도 실패하면 0 이 아닌 값으로 끝난다.
#
# kotlinc 는 GitHub 릴리스 zip 을 받아 캐시에 푼다(전역 설치 없음, sha256 대조):
#   ${ONESIGHT_TOOL_CACHE:-~/.cache/onesight-sdk}/kotlinc-<버전>
#
# 사용:  Scripts/consumer-compat-check.sh
#        CONSUMER_KOTLIN_VERSIONS="2.1.21" Scripts/consumer-compat-check.sh   (검사할 Kotlin 버전 바꾸기)
#        SKIP_BUILD=1 Scripts/consumer-compat-check.sh   (AAR·클래스패스를 이미 만든 단계에서)
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

# --- 1. 빌드 ---------------------------------------------------------------------------------
if [[ "${SKIP_BUILD:-}" == "1" ]]; then
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
import co.onecheck.ones1ght.android.TriggersListener
import co.onecheck.ones1ght.android.ZoneListener
import co.onecheck.ones1ght.android.model.Building
import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProviderDelegate

/** 필수 멤버만 구현 — 기본 구현이 있는 pause·resume·apply 등은 오버라이드하지 않는다. */
class KotlinProvider : PositioningProvider {
    override var delegate: PositioningProviderDelegate? = null
    override fun start() {}
    override fun stop() {}
}

/** 권한 요청 — suspend 판(androidx.activity 의 ComponentActivity 를 받는다: api 의존이 고객 클래스패스에 있는지). */
suspend fun kotlinPermissions(activity: ComponentActivity): Boolean =
    OneS1ght.permissions(activity) == PermissionStatus.AUTHORIZED

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
    return "running=$running version=${OneS1ght.SDK_VERSION}"
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
        OneS1ght.permissions(activity, new Callback<PermissionStatus>() {
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
}
JAVA

step "Java 소비자 컴파일 (javac --release 8)"
# -Xlint:-options: --release 8 의 "곧 제거" 경고만 끈다. 그 외 경고는 -Werror 로 실패.
javac --release 8 -Xlint:all -Xlint:-options -Xlint:-classfile -Werror \
    -classpath "$CP" -d "$WORK/java/out" "$WORK/java/src/consumer/JavaConsumer.java" \
    > "$WORK/java/log.txt" 2>&1 || { cat "$WORK/java/log.txt" >&2; die "Java 소비자 컴파일 실패"; }
ok "javac $(javac -version 2>&1 | awk '{print $2}') --release 8 컴파일 통과"

echo "고객 앱 호환 검사 통과 (Kotlin ${KOTLIN_VERSIONS// / · } · Java 8)."
