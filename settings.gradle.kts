pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// gpa-dltdoa 엔진 저장소는 계정(geoplanNexusUrl/User/Password)이 셋 다 있을 때만 추가한다.
// repositoriesMode 가 PREFER_SETTINGS 라 프로젝트(onesight/build.gradle.kts)에서 저장소를
// 추가해도 무시되므로, 반드시 여기(settings)에서 등록해야 실제로 쓰인다.
val geoplanNexusUrl = providers.gradleProperty("geoplanNexusUrl").orNull
val geoplanNexusUser = providers.gradleProperty("geoplanNexusUser").orNull
val geoplanNexusPassword = providers.gradleProperty("geoplanNexusPassword").orNull
val hasGeoplanEngineCreds =
    !geoplanNexusUrl.isNullOrBlank() && !geoplanNexusUser.isNullOrBlank() && !geoplanNexusPassword.isNullOrBlank()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        if (hasGeoplanEngineCreds) {
            maven {
                url = uri(geoplanNexusUrl!!)
                // 엔진 저장소가 http 로 제공된다 — 이 저장소 하나에만 허용하고, 엔진 그룹만 여기서 찾는다.
                isAllowInsecureProtocol = geoplanNexusUrl.startsWith("http://")
                content { includeGroup("kr.geoplan.android.lib") }
                credentials {
                    username = geoplanNexusUser
                    password = geoplanNexusPassword
                }
            }
        }
    }
}

rootProject.name = "OneS1ght-Android-SDK"

include(":onesight")
include(":engine-stub")
