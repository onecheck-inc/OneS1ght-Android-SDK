import org.gradle.api.artifacts.Configuration

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

    testImplementation(libs.junit)
}
