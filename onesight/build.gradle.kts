import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import java.security.MessageDigest
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "co.onecheck.ones1ght.android"
    compileSdk = 37

    defaultConfig {
        // 패키지 최소 사양 — Android 8.0(API 26). "설치는 넓게, 측위는 지원 OS 에서만"(iOS 와 같다:
        // 패키지 iOS 18 · 측위 iOS 27). 측위 엔진은 API 37(Android 17)의 android.ranging 을 쓰므로
        // 엔진에 닿는 길은 전부 런타임 게이트(MIN_POSITIONING_SDK · SDK_INT 검사)를 지나야 한다 —
        // 엔진 감싸개(IntelligenceHubEngine · createBuiltInProvider)는 @RequiresApi(37) 이라
        // 게이트를 빠뜨리면 lint NewApi 가 잡는다.
        minSdk = 26
        // consumerProguardFiles 는 아래 엔진 분기(계정 있음/없음)에서 등록한다 —
        // 계정이 있으면 consumer-rules.pro 를 그대로 쓰지 않고 엔진 proguard.txt 와
        // 합친 파일 하나로 대체한다(둘 다 등록하면 consumer-rules.pro 내용이 두 번 실린다).
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // AGP 9 는 라이브러리 모듈의 defaultConfig.targetSdk 를 없앴다(그건 원래도 그 모듈
        // 자신의 테스트에만 적용됐다) — 여기 testOptions 와 아래 lint 로 옮겨졌다.
        targetSdk = 37
    }

    lint {
        targetSdk = 37
    }

    sourceSets {
        named("main") {
            kotlin.directories.add("src/main/kotlin")
        }
        named("test") {
            kotlin.directories.add("src/test/kotlin")
            kotlin.directories.add("src/test/java")
        }
    }
}

kotlin {
    explicitApi()

    // 고객 앱 Kotlin 호환 — 오래된 Kotlin 컴파일러(1.9·2.0)로 빌드하는 앱도 이 AAR 을 읽을 수 있게
    // 언어·API 수준을 2.0 으로 낮춘다(메타데이터 2.0.0 으로 기록 → Kotlin 1.9+ 가 읽음). 컴파일러 자체는
    // AGP 내장 Kotlin(libs.versions.toml 의 kotlin) 그대로다. apiVersion 2.0 이라 표준 라이브러리도
    // 2.0 에 있는 것만 쓸 수 있다(2.1+ API 를 쓰면 컴파일 오류).
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        // 인터페이스 기본 구현을 JVM default 메서드로 내보낸다(+ 옛 Kotlin 구현체용 DefaultImpls 도 함께).
        // Kotlin 2.2 는 언어 수준 2.2 일 때만 이게 기본값이라, 2.0 으로 낮추면 명시해야 한다 — 빠지면
        // Java 로 PositioningProvider 를 구현할 때 pause()·resume() 등을 전부 오버라이드해야 한다
        // (JavaPositioningProviderCompatTest 가 잡는다).
        jvmDefault.set(JvmDefaultMode.ENABLE)
    }
    // 자동으로 붙는 kotlin-stdlib 의존 버전(POM·.module 에 실리는 값). 고객이 더 새 stdlib 를 쓰면 그쪽으로 올라간다.
    coreLibrariesVersion = "2.0.21"
}

// ---------------------------------------------------------------------------
// 측위 엔진(통합 엔진 gpa-ihub + 그 하위 gpa-prm · gpa-dltdoa) 의존 전환
//
// ~/.gradle/gradle.properties 에 geoplanNexusUrl/User/Password 셋 다 있으면 실제
// 엔진 AAR 3개를 받아 각각의 classes.jar(+ libs/*.jar)를 fat-aar 방식으로 싣는다(고객 빌드에는
// 엔진 저장소가 절대 나오지 않는다). 없으면 컴파일 전용 스텁(:engine-stub) 을 쓴다.
// 어느 쪽이든 assembleRelease 는 계정 없이는 실패해야 한다 — 스텁이 실린 release
// AAR 이 배포되는 사고를 막기 위해서다.
//
// 세 AAR 모두 res/ 가 없고 R.txt 가 비어 있다(1.1.0 · 2.0.0 · 2.1.0 기준 확인) — 클래스만 실으면 된다.
// 엔진 매니페스트의 권한은 우리 매니페스트(src/main/AndroidManifest.xml)가 같은 것을 선언한다.
// gpa-prm 매니페스트의 usesCleartextTraffic 은 싣지 않는다 — 엔진 서버 통신은 https 뿐이다.
// ---------------------------------------------------------------------------

val geoplanNexusUrl = providers.gradleProperty("geoplanNexusUrl").orNull
val geoplanNexusUser = providers.gradleProperty("geoplanNexusUser").orNull
val geoplanNexusPassword = providers.gradleProperty("geoplanNexusPassword").orNull
val hasGeoplanEngineCreds =
    !geoplanNexusUrl.isNullOrBlank() && !geoplanNexusUser.isNullOrBlank() && !geoplanNexusPassword.isNullOrBlank()

if (hasGeoplanEngineCreds) {
    // 저장소는 settings.gradle.kts 의 dependencyResolutionManagement 에서 등록한다
    // (repositoriesMode=PREFER_SETTINGS 라 여기서 선언해도 무시된다).
    //
    // 아래는 전부 "설정 시점(configuration time)에도, 태스크 그래프 계산 시점에도 Nexus 를
    // 건드리지 않는다" 는 원칙으로 짰다 — geoplanEngine Configuration 은 extractGeoplanEngineAar
    // 의 doLast 실행 안에서만 resolve 한다(Copy 태스크의 from(zipTree(config)) 는 태스크 그래프를
    // 짤 때 Gradle 이 소스의 buildDependencies 를 알아내려고 조기 resolve 해버려서 쓰지 않았다).
    // 그래서 계정이 있어도 `help`·`tasks` 처럼 빌드가 필요 없는 명령은 네트워크를 타지 않고,
    // 실제로 onesight 를 컴파일·번들링하는 태스크(예: assembleDebug)를 실행할 때만 Nexus 를
    // 때린다 — extractGeoplanEngineAar 자체가 onesight 컴파일/번들링 태스크의 입력이라
    // implementation(files(...).builtBy(extractEngineAar)) 로 수동 순서만 걸면 충분하다.
    val geoplanEngine: Configuration = configurations.create("geoplanEngine") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false // AAR 자체만 받는다 — 전이 의존은 아래 고정 버전으로 별도 선언
    }

    dependencies {
        // 통합 엔진이 하위 두 엔진(판정 · DL-TDoA)을 런타임 의존으로 부른다 — POM 에 적힌 판 그대로.
        add(geoplanEngine.name, "kr.geoplan.android.lib:gpa-ihub:1.1.0")
        add(geoplanEngine.name, "kr.geoplan.android.lib:gpa-prm:2.0.0")
        add(geoplanEngine.name, "kr.geoplan.android.lib:gpa-dltdoa:2.1.0")
    }

    // 엔진 AAR 무결성 고정(sha256) — 공급사 저장소에서 받은 AAR 을 우리 서명본에 그대로 싣기 때문에,
    // 받는 중 바꿔치기·저장소 쪽 덮어쓰기가 있으면 풀기 전에 빌드를 멈춘다. 전체 verification-metadata 는
    // 모든 의존성을 막으므로 쓰지 않고, 엔진 AAR 3개만 여기서 대조한다.
    // 엔진 판을 올리면 위 dependencies 와 이 표를 함께 고친다(`shasum -a 256 <AAR>` 값).
    val engineAarSha256 = mapOf(
        "gpa-ihub-1.1.0.aar" to "f8e55a1e239b54bfad399698f4bb1c57741618147adf35c4730c1917a9225d2d",
        "gpa-prm-2.0.0.aar" to "c1ce0dbcaf44762165a415f2daa7a02b5f482e4a95e3617623ff22c37241f729",
        "gpa-dltdoa-2.1.0.aar" to "48263fd40b3083b9599cc0b83206da09610625bb306e40c7a1a6207e2e58f964",
    )

    // 풀기 결과: jars/<AAR 이름>.jar(각 AAR 의 classes.jar — 이름이 셋 다 같아 AAR 이름으로 바꾼다)
    //          + jars/<libs 안 jar 이름>(측위 필터 등 내부 라이브러리) · rules/<AAR 이름>.txt(proguard.txt 가 있으면)
    val engineExtractedDir = layout.buildDirectory.dir("geoplanEngine/extracted")
    val extractEngineAar = tasks.register("extractGeoplanEngineAar") {
        val outputDir = engineExtractedDir
        // 해시 표가 바뀌면(엔진 판 올림) 다시 풀고 다시 대조한다.
        inputs.property("engineAarSha256", engineAarSha256)
        outputs.dir(outputDir)
        doLast {
            val out = outputDir.get().asFile
            out.deleteRecursively()
            val jarsDir = File(out, "jars").apply { mkdirs() }
            val rulesDir = File(out, "rules").apply { mkdirs() }
            // 여기(실행 시점)에서만 resolve
            val engineAars = geoplanEngine.files.sortedBy { it.name }
            // 해시 대조 — 표에 없는 AAR(판이 바뀐 경우 포함)·표에 있는데 안 받아진 AAR·해시 불일치 모두 멈춘다.
            val resolvedNames = engineAars.map { it.name }.toSet()
            val missing = engineAarSha256.keys - resolvedNames
            if (missing.isNotEmpty()) {
                throw GradleException("엔진 AAR 이 받아지지 않았다: ${missing.sorted()} — 판을 바꿨다면 engineAarSha256 표도 고친다")
            }
            for (aar in engineAars) {
                val expected = engineAarSha256[aar.name]
                    ?: throw GradleException("엔진 AAR ${aar.name} 의 sha256 이 고정돼 있지 않다 — engineAarSha256 표에 더한다")
                val digest = MessageDigest.getInstance("SHA-256")
                aar.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    throw GradleException(
                        "엔진 AAR ${aar.name} 해시 불일치(기대 $expected, 실제 $actual) — 받은 파일이 바뀌었다. " +
                            "공급사에 판 변경 여부를 확인하기 전엔 싣지 않는다",
                    )
                }
            }
            for (aar in engineAars) {
                val base = aar.name.removeSuffix(".aar")
                val tmp = File(out, "tmp/$base")
                copy {
                    from(zipTree(aar))
                    into(tmp)
                }
                // 리소스가 생기면 fat 방식으로는 못 싣는다 — 조용히 빠뜨리지 않고 멈춘다.
                val res = File(tmp, "res")
                if (res.exists() && res.walkTopDown().any { it.isFile }) {
                    throw GradleException("$base 에 res/ 가 생겼다 — fat 방식으로 실을 수 없다(빌드 방식 재검토 필요)")
                }
                // 이름이 겹치면 덮어쓰지 않고 멈춘다 — 조용히 덮으면 엔진 하나의 클래스가 통째로 빠진다.
                fun addJar(src: File, name: String) {
                    val dest = File(jarsDir, name)
                    if (dest.exists()) {
                        throw GradleException("엔진 jar 이름 충돌: $name ($base) — 두 엔진 AAR 이 같은 이름의 jar 를 싣는다")
                    }
                    src.copyTo(dest)
                }
                File(tmp, "classes.jar").takeIf { it.exists() }?.let { addJar(it, "$base.jar") }
                File(tmp, "libs").listFiles { f -> f.name.endsWith(".jar") }?.sortedBy { it.name }?.forEach {
                    addJar(it, it.name)
                }
                File(tmp, "proguard.txt").takeIf { it.exists() }?.copyTo(File(rulesDir, "$base.txt"), overwrite = true)
            }
            File(out, "tmp").deleteRecursively()
        }
    }

    // 엔진 jar 전부 — classes.jar 3개 + 내부 라이브러리 jar. 이름은 판마다 바뀔 수 있어 패턴으로 잡는다.
    val engineJars = engineExtractedDir.map { dir -> dir.asFileTree.matching { include("jars/*.jar") } }

    // 엔진 AAR 의 proguard.txt 가 있으면 우리 consumer-rules.pro 와 합쳐 별도 머지본으로
    // 내보낸다(레포에 커밋된 consumer-rules.pro 원본은 건드리지 않는다). 이 머지본이
    // consumer-rules.pro 자리를 그대로 대체한다 — 둘 다 등록하면 내용이 두 번 실린다.
    // (1.1.0 · 2.0.0 · 2.1.0 에는 proguard.txt 가 없다 — 생기면 자동으로 실린다.)
    val mergedConsumerRules = layout.buildDirectory.file("geoplanEngine/merged-consumer-rules.pro")
    val mergeEngineProguardRules = tasks.register("mergeGeoplanEngineProguardRules") {
        dependsOn(extractEngineAar)
        val ownRules = file("consumer-rules.pro")
        val engineRulesDir = engineExtractedDir.map { it.dir("rules") }
        inputs.file(ownRules)
        outputs.file(mergedConsumerRules)
        doLast {
            val merged = mergedConsumerRules.get().asFile
            merged.parentFile.mkdirs()
            merged.writeText(ownRules.readText())
            engineRulesDir.get().asFile.listFiles()?.sortedBy { it.name }?.forEach {
                merged.appendText("\n# --- ${it.nameWithoutExtension} engine consumer rules ---\n")
                merged.appendText(it.readText())
            }
        }
    }

    android.defaultConfig.consumerProguardFile(mergedConsumerRules.get().asFile)
    tasks.named("preBuild") {
        dependsOn(mergeEngineProguardRules)
    }

    dependencies {
        implementation(files(engineJars).builtBy(extractEngineAar))
        // 전이 의존은 엔진 POM 에 적힌 버전 그대로 implementation 한다
        // (gradle/libs.versions.toml). Maven Central 공개 좌표라 고객 빌드에
        // 엔진 저장소가 노출되지는 않는다.
        // 싣지 않는 것 — 엔진 바이트코드가 참조하지 않는 UI 라이브러리다(javap 로 확인):
        //   gpa-prm 2.0.0 의 androidx.constraintlayout · gpa-dltdoa 2.1.0 의 androidx.appcompat · material.
        // okhttp 는 우리 것(4.12.0 ≥ 엔진 4.9.1, 같은 4.x)을 함께 쓴다. androidx.annotation 은 주석 전용이라
        // 런타임에 필요 없고 androidx.activity 가 이미 가져온다.
        implementation(libs.geoplan.engine.androidx.core)
        implementation(libs.geoplan.engine.gson)
        implementation(libs.geoplan.engine.jts.vividsolutions)
        implementation(libs.geoplan.engine.commons.math3)
        implementation(libs.geoplan.engine.jts.core)
        implementation(libs.geoplan.engine.slf4j.api)
    }
} else {
    dependencies {
        compileOnly(project(":engine-stub"))
    }

    android.defaultConfig.consumerProguardFiles("consumer-rules.pro")

    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        doLast {
            throw GradleException("gpa engine missing: set geoplanNexus* in ~/.gradle/gradle.properties")
        }
    }

    // 배포(publish*) 도 계정 없이는 막는다 — 스텁 빌드가 Maven Central·mavenLocal 에 올라가는 사고 방지.
    // release AAR 이 preReleaseBuild 에서 이미 막히지만, 태스크 그래프가 정해진 순간(어떤 태스크도 돌기
    // 전)에 한 번 더 막는다 — sources·javadoc jar 처럼 AAR 을 거치지 않는 태스크도 돌지 않는다.
    // `tasks`·테스트처럼 배포가 아닌 명령은 계정 없이도 돈다.
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.startsWith("publish") }) {
            throw GradleException("gpa engine missing: publishing requires geoplanNexus* in ~/.gradle/gradle.properties")
        }
    }
}

// ---------------------------------------------------------------------------
// Maven Central 배포 — com.ones1ght.sdk:android:<SDK_VERSION>
//
// 버전의 단일 출처는 OneS1ght.SDK_VERSION 이다(Scripts/sdk-version.sh · check-release.sh 가 같은 줄을
// 읽는다). 여기서도 그 줄을 읽어 좌표에 쓴다 — 판올림 때 고칠 곳이 늘지 않는다.
//
// 계정·서명 값은 레포에 두지 않는다. vanniktech 표준 이름으로 ~/.gradle/gradle.properties 또는
// ORG_GRADLE_PROJECT_<이름> 환경변수에서 읽는다(RELEASING.md 참고):
//   mavenCentralUsername · mavenCentralPassword       (Central Portal 사용자 토큰)
//   signingInMemoryKey · signingInMemoryKeyId · signingInMemoryKeyPassword   (GPG 개인키)
// 서명 키가 없으면 mavenLocal 배포는 서명 없이 되고(로컬 확인용), Maven Central 업로드는 막는다.
// ---------------------------------------------------------------------------
val sdkVersion: String = run {
    val src = file("src/main/kotlin/co/onecheck/ones1ght/android/OneS1ght.kt").readText()
    Regex("""SDK_VERSION: String = "([^"]+)"""").find(src)?.groupValues?.get(1)
        ?: throw GradleException("OneS1ght.SDK_VERSION 을 찾지 못함 — OneS1ght.kt 의 선언 모양이 바뀌었는지 확인")
}
val hasSigningKey = !providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )
    publishToMavenCentral()
    if (hasSigningKey) signAllPublications()

    coordinates("com.ones1ght.sdk", "android", sdkVersion)

    pom {
        name.set("OneS1ght Android SDK")
        description.set(
            "OneS1ght indoor location intelligence SDK for Android — UWB indoor positioning, " +
                "zone enter/exit/dwell events and visit data collection for OneS1ght customers.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/onecheck-inc/OneS1ght-Android-SDK")
        licenses {
            license {
                name.set("OneS1ght SDK License")
                url.set("https://github.com/onecheck-inc/OneS1ght-Android-SDK/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("onecheck")
                name.set("OneCheck Inc.")
                email.set("onesight-support@onecheck.co.kr")
                organization.set("OneCheck Inc.")
                organizationUrl.set("https://ones1ght.com")
            }
        }
        scm {
            url.set("https://github.com/onecheck-inc/OneS1ght-Android-SDK")
            connection.set("scm:git:https://github.com/onecheck-inc/OneS1ght-Android-SDK.git")
            developerConnection.set("scm:git:ssh://git@github.com/onecheck-inc/OneS1ght-Android-SDK.git")
        }
    }
}

// Maven Central 은 서명 없는 판을 받지 않는다 — 키 없이 Central 태스크를 부르면 태스크 그래프가 정해진
// 순간(빌드·업로드 전)에 멈춘다. mavenLocal 배포는 영향 없다.
if (!hasSigningKey) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.contains("MavenCentral") }) {
            throw GradleException("Maven Central 배포에는 서명 키(signingInMemoryKey)가 필요하다 — RELEASING.md 참고")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // 공개 API UwbPositioningProvider 가 상태를 StateFlow(kotlinx.coroutines.flow)로 노출하므로 api 로 싣는다(0.0.5~).
    // 1.9.0 은 kotlin-stdlib 2.0.0 을 요구 — 고객 Kotlin 1.9+ 약속 안이다(consumer-compat-check 가 확인).
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.process)
    // 공개 API permissions(activity: ComponentActivity) 가 노출하므로 api 로 싣는다.
    api(libs.androidx.activity)

    testImplementation(libs.junit)
    // JavaApiSurfaceTest — 공개 여부를 Kotlin 메타데이터로 판정(internal 이 JVM 에선 public 이라)
    testImplementation(libs.kotlin.metadata.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// ---------------------------------------------------------------------------
// 고객 컴파일 클래스패스 내보내기 — Scripts/consumer-compat-check.sh 전용.
//
// 고객 앱이 이 SDK 를 컴파일할 때 보는 것 = SDK 의 api 의존과 그 전이 api 의존뿐이다(implementation 은
// 안 보인다). 그 집합을 안드로이드 컴파일(java-api) 기준으로 풀어 build/consumer-compat/api-deps 에 복사한다.
// 스크립트가 여기 있는 .aar/.jar 만으로 Kotlin·Java 소비자 코드를 컴파일해 본다.
// ---------------------------------------------------------------------------
val consumerApiClasspath: Configuration = configurations.create("consumerApiClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations["api"])
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.ANDROID))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.androidJvm)
    }
}

// 안드로이드 앱 빌드(AGP)는 컴파일 클래스패스 버전을 런타임 클래스패스 버전에 맞춘다 — 그래서 고객이 컴파일 때
// 실제로 보는 버전은 "api 선언 버전" 이 아니라 "implementation 까지 합쳐 올라간 버전" 이다(예: kotlin-stdlib).
// 같은 효과를 내려고 런타임 클래스패스와 일치하게 푼다.
afterEvaluate {
    consumerApiClasspath.shouldResolveConsistentlyWith(configurations["debugRuntimeClasspath"])
}

tasks.register<Sync>("exportConsumerApiClasspath") {
    description = "고객 컴파일 클래스패스(api 의존)를 build/consumer-compat/api-deps 로 복사한다."
    from(consumerApiClasspath)
    into(layout.buildDirectory.dir("consumer-compat/api-deps"))
}
