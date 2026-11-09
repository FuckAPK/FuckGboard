plugins {
    id("fuck.android.application")
    id("fuck.xposed.legacy")
}

android {
    namespace = "org.lyaaz.fuckgboard"

    defaultConfig {
        applicationId = "org.lyaaz.fuckgboard"
        versionCode = 4
        versionName = "1.3"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
