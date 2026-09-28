import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "co.onecheck.ones1ght.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    val geoplanEngine: Configuration = configurations.create("geoplanEngine") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = true
    }

    dependencies {
        add(geoplanEngine.name, "kr.geoplan.android.lib:gpa-dltdoa:2.1.0")
    }

    val engineResolvedArtifacts = geoplanEngine.resolvedConfiguration.resolvedArtifacts
    val engineArtifact = engineResolvedArtifacts.single {
        it.moduleVersion.id.group == "kr.geoplan.android.lib" && it.moduleVersion.id.name == "gpa-dltdoa"
    }

    val extractEngineAar = tasks.register<Copy>("extractGeoplanEngineAar") {
        from(zipTree(engineArtifact.file))
        into(layout.buildDirectory.dir("geoplanEngine/extracted"))
    }

    val engineClassesJar = layout.buildDirectory.file("geoplanEngine/extracted/classes.jar")

    // 엔진 AAR 의 proguard.txt 가 있으면 우리 consumer-rules.pro 와 합쳐 별도 머지본으로 내보낸다
    // (레포에 커밋된 consumer-rules.pro 원본은 건드리지 않는다).
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

    // 전이 의존은 엔진 POM 에 적힌 버전 그대로 implementation 한다(Maven Central 공개 좌표라
    // 고객 빌드에 Geoplan 저장소가 노출되지 않는다).
    val transitiveCoordinates = listOf(
        "org.apache.commons:commons-math3",
        "org.locationtech.jts:jts-core",
        "org.slf4j:slf4j-api",
    ).map { coordinate ->
        val (group, name) = coordinate.split(":")
        val resolvedVersion = engineResolvedArtifacts
            .map { it.moduleVersion.id }
            .firstOrNull { it.group == group && it.name == name }
            ?.version
            ?: error("engine POM 에서 $coordinate 버전을 찾지 못했다")
        "$coordinate:$resolvedVersion"
    }

    dependencies {
        implementation(files(engineClassesJar).builtBy(extractEngineAar))
        transitiveCoordinates.forEach { implementation(it) }
    }

    tasks.named("preBuild") {
        dependsOn(mergeEngineProguardRules)
    }
} else {
    dependencies {
        compileOnly(project(":engine-stub"))
    }

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
