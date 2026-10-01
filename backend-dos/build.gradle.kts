plugins {
    id("com.android.library")
}

apply(from = rootProject.file("shared/gradle/android-module.gradle"))

android {
    namespace = "com.mrjackspade.kairodos.backend"

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DKAIRO_STAGING_DEPS=" +
                    providers.gradleProperty("stagingDeps").orElse(
                        rootProject.file(".downloads/staging-deps/installed/arm64-kairo-android").absolutePath).get()
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DKAIRO_OPL_WORKER=" +
                    if (providers.gradleProperty("oplWorker").orNull == "true") "ON" else "OFF"
                arguments += "-DKAIRO_OPL_VERIFY=" +
                    if (providers.gradleProperty("oplVerify").orNull == "true") "ON" else "OFF"
                arguments += "-DKAIRO_GUEST_PROFILE=" +
                    if (providers.gradleProperty("guestProfile").orNull == "true") "ON" else "OFF"
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }
}
