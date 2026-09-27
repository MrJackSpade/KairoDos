plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mrjackspade.kairodos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.loxifi.kairodos"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":frontend"))
}
