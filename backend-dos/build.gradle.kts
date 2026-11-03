plugins {
    id("com.android.library")
}

apply(from = rootProject.file("shared/gradle/android-module.gradle"))

val kairoAbi = extra["kairoAbi"] as String
val stagingTriplet = mapOf("arm64-v8a" to "arm64-kairo-android", "x86_64" to "x64-kairo-android").getValue(kairoAbi)

android {
    namespace = "com.mrjackspade.kairodos.backend"

    defaultConfig {
        externalNativeBuild {
            cmake {
                // The build's ABI (kairoAbi, shared gradle/android-module.gradle): its dependencies are
                // built for it by tools/PrepareStagingDependencies.ps1 -Abi.
                arguments += "-DKAIRO_STAGING_DEPS=" +
                    providers.gradleProperty("stagingDeps").orElse(
                        rootProject.file(".downloads/staging-deps/installed/$stagingTriplet").absolutePath).get()
                arguments += "-DANDROID_STL=c++_shared"
                // The profile is recorded on arm64 (pgo/), so another ABI builds without it.
                arguments += "-DKAIRO_PGO=" +
                    if (providers.gradleProperty("pgo").orElse((kairoAbi == "arm64-v8a").toString()).get() == "true") "ON" else "OFF"
                arguments += "-DKAIRO_OPL_WORKER=" +
                    if (providers.gradleProperty("oplWorker").orElse("true").get() == "true") "ON" else "OFF"
                arguments += "-DKAIRO_OPL_VERIFY=" +
                    if (providers.gradleProperty("oplVerify").orNull == "true") "ON" else "OFF"
                arguments += "-DKAIRO_OPL_STRESS=" +
                    if (providers.gradleProperty("oplStress").orNull == "true") "ON" else "OFF"
                arguments += "-DKAIRO_GUEST_PROFILE=" +
                    if (providers.gradleProperty("guestProfile").orNull == "true") "ON" else "OFF"
            }
        }
        ndk {
            abiFilters += kairoAbi
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }
}
