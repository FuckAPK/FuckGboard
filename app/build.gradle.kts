plugins {
    id("fuck.android.application")
    id("fuck.compose")
    id("fuck.xposed.modern")
}

android {
    namespace = "org.lyaaz.fuckgboard"

    defaultConfig {
        applicationId = "org.lyaaz.fuckgboard"
    }

    buildFeatures {
        aidl = true
    }

    androidResources {
        localeFilters.add("zh-rCN")
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.material)
    implementation(libs.libsu.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation("junit:junit:4.13.2")
}
