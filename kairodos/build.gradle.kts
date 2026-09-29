plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = rootProject.file("shared/gradle/android-module.gradle"))

android {
    namespace = "com.mrjackspade.kairodos"

    defaultConfig {
        applicationId = "com.loxifi.kairodos"
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "com.mrjackspade.kairodos.CatalogUpdateInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":frontend"))
    implementation(project(":backend-dos"))
}
