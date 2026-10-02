import java.util.Properties

plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

// android.jar 위치 — 예전엔 ANDROID_HOME 이 없으면 macOS 기본 경로(~/Library/Android/sdk)로 고정돼 리눅스·윈도
// 개발 머신에서 스텁이 컴파일되지 않았다(감사 SP-C10). AGP 와 같은 순서로 찾는다:
// local.properties 의 sdk.dir → ANDROID_HOME → ANDROID_SDK_ROOT → OS 별 기본 경로.
val androidSdkDir: String = run {
    val local = rootProject.file("local.properties")
    val fromLocal = if (local.isFile) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else {
        null
    }
    val home = System.getProperty("user.home")
    val osDefault = when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "$home/Library/Android/sdk"
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "${System.getenv("LOCALAPPDATA")}/Android/Sdk"
        else -> "$home/Android/Sdk"
    }
    fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: osDefault
}

dependencies {
    compileOnly(files("$androidSdkDir/platforms/android-37.0/android.jar"))
}
