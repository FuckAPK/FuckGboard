plugins {
    id("fuck.android.application")
    id("fuck.xposed.modern")
}

android {
    namespace = "org.lyaaz.fuckgboard"

    defaultConfig {
        applicationId = "org.lyaaz.fuckgboard"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
