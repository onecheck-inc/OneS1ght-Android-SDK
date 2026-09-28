import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "co.onecheck.ones1ght.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        // consumerProguardFiles 는 아래 gpa-dltdoa 분기(계정 있음/없음)에서 등록한다 —
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
// gpa-dltdoa 엔진 의존 전환
//
// ~/.gradle/gradle.properties 에 geoplanNexusUrl/User/Password 셋 다 있으면 실제
// 엔진 AAR 을 받아 classes.jar 를 fat-aar 방식으로 싣는다(고객 빌드에는 Geoplan
// 저장소가 절대 나오지 않는다). 없으면 컴파일 전용 스텁(:engine-stub) 을 쓴다.
// 어느 쪽이든 assembleRelease 는 계정 없이는 실패해야 한다 — 스텁이 실린 release
// AAR 이 배포되는 사고를 막기 위해서다.
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
        add(geoplanEngine.name, "kr.geoplan.android.lib:gpa-dltdoa:2.1.0")
    }

    val engineExtractedDir = layout.buildDirectory.dir("geoplanEngine/extracted")
    val extractEngineAar = tasks.register("extractGeoplanEngineAar") {
        val outputDir = engineExtractedDir
        outputs.dir(outputDir)
        doLast {
            val aarFile = geoplanEngine.singleFile // 여기(실행 시점)에서만 resolve
            copy {
                from(zipTree(aarFile))
                into(outputDir.get())
            }
        }
    }

    val engineClassesJar = layout.buildDirectory.file("geoplanEngine/extracted/classes.jar")

    // 엔진 AAR 의 proguard.txt 가 있으면 우리 consumer-rules.pro 와 합쳐 별도 머지본으로
    // 내보낸다(레포에 커밋된 consumer-rules.pro 원본은 건드리지 않는다). 이 머지본이
    // consumer-rules.pro 자리를 그대로 대체한다 — 둘 다 등록하면 내용이 두 번 실린다.
    val mergedConsumerRules = layout.buildDirectory.file("geoplanEngine/merged-consumer-rules.pro")
    val mergeEngineProguardRules = tasks.register("mergeGeoplanEngineProguardRules") {
        dependsOn(extractEngineAar)
        val ownRules = file("consumer-rules.pro")
        val engineRules = layout.buildDirectory.file("geoplanEngine/extracted/proguard.txt")
        inputs.file(ownRules)
        outputs.file(mergedConsumerRules)
        doLast {
            val merged = mergedConsumerRules.get().asFile
            merged.parentFile.mkdirs()
            merged.writeText(ownRules.readText())
            val engineRulesFile = engineRules.get().asFile
            if (engineRulesFile.exists()) {
                merged.appendText("\n# --- gpa-dltdoa engine consumer rules ---\n")
                merged.appendText(engineRulesFile.readText())
            }
        }
    }

    android.defaultConfig.consumerProguardFile(mergedConsumerRules.get().asFile)
    tasks.named("preBuild") {
        dependsOn(mergeEngineProguardRules)
    }

    dependencies {
        implementation(files(engineClassesJar).builtBy(extractEngineAar))
        // 전이 의존은 엔진 POM 에 적힌 버전 그대로 implementation 해야 하지만, 계정이 없어
        // 실제 POM 을 지금 읽을 수 없다 — gradle/libs.versions.toml 에 추정치로 고정해뒀다
        // (주석에 "verify when creds exist"). Maven Central 공개 좌표라 고객 빌드에
        // Geoplan 저장소가 노출되지는 않는다.
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
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
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
