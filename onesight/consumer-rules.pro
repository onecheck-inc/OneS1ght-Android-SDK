# OneS1ght SDK — 고객 앱 R8/ProGuard 규칙 (AAR proguard.txt 로 실려 고객 앱 minify 에 자동 적용된다)
#
# 확인 방법: Scripts/consumer-compat-check.sh 의 MINIFIED=1 모드 — mavenLocal 배포본을 minifyEnabled 앱으로
# 빌드해 아래 클래스·필드가 원래 이름으로 남는지 R8 매핑으로 본다.

# --- 내장 측위 엔진 ---------------------------------------------------------------------------
# 엔진은 서버 응답(층 인프라·지오펜스)을 Gson 으로 public 필드 POJO 에 바로 푼다(Gson.fromJson(String, Class)).
# 필드 이름이 JSON 키라, R8 이 이름을 바꾸거나 "안 읽힌다" 고 필드를 지우면 층·구역이 조용히 비어 버린다.
# 엔진 AAR 은 규칙을 싣지 않으므로 여기서 엔진 패키지 전체를 그대로 둔다(엔진은 수백 KB 라 축소 이득도 작다).
-keep class kr.geoplan.** { *; }
-keep class kr.co.geoplan.** { *; }

# --- Gson (2.10.1 은 R8 규칙을 싣지 않는다) — 공식 권장 규칙 -------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-dontwarn sun.misc.**
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# --- 엔진 내부 라이브러리의 컴파일 전용 참조 --------------------------------------------------------
# 측위 필터 jar 가 lombok @NonNull 을 CLASS 보존 주석으로 남겨 두었다. 런타임에 필요 없는데, 이 줄이 없으면
# 고객 앱 minify 가 "Missing class lombok.NonNull" 로 **빌드 자체가 실패한다**(AGP 8+ 는 누락 클래스를 오류로 본다).
-dontwarn lombok.**
# 측위 엔진이 slf4j-api 로 로그를 남긴다. 바인딩(StaticLoggerBinder)은 일부러 싣지 않는다 — slf4j 1.7 은 바인딩이
# 없으면 NOP 로거로 조용히 떨어진다(런타임에 NoClassDefFoundError 를 잡아 처리). R8 에게만 없다고 알린다.
-dontwarn org.slf4j.impl.**

# --- SDK 진입점 -------------------------------------------------------------------------------
# 0.0.7 부터 조립·상태가 SdkWiring 으로 빠져 OneS1ght 가 얇아지자, 고객 앱 R8 이 정적 메서드를 앱 코드로 인라인하고
# 클래스 자체를 지웠다(동작은 같다). 0.0.6 까지는 상태를 들고 있어 저절로 남았다 — 같은 모양을 규칙으로 고정한다.
# 범위는 이 한 클래스뿐이다: SDK 는 고객 앱에 얹혀 사는 쪽이라, 앱 코드의 축소·난독화에는 손대지 않는다.
-keep class co.onecheck.ones1ght.android.OneS1ght { public *; }
# OneS1ght 자체 코드는 규칙이 필요 없다 — 직렬화는 컴파일 시점에 생성되고(kotlinx.serialization 이
# 자기 규칙을 싣는다), 네트워크는 OkHttp 가 자기 규칙을 싣는다.
