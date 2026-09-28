plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    compileOnly(files("${System.getenv("ANDROID_HOME") ?: System.getProperty("user.home") + "/Library/Android/sdk"}/platforms/android-37.0/android.jar"))
}
