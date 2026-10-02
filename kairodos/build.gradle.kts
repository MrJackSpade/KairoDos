plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = rootProject.file("shared/gradle/android-module.gradle"))

android {
    namespace = "com.mrjackspade.kairodos"

    defaultConfig {
        applicationId = "com.loxifi.kairodos"
        versionCode = providers.gradleProperty("kairodosVersionCode").orNull?.toInt() ?: 900
        versionName = providers.gradleProperty("kairodosVersionName").orNull ?: "0.9.0"
        testInstrumentationRunner = "com.mrjackspade.kairodos.CatalogUpdateInstrumentation"
        externalNativeBuild {
            cmake {
                arguments += "-DKAIRO_OPTIMIZED_PROFILE=" +
                    if (providers.gradleProperty("presentationProfile").orNull == "true") "ON" else "OFF"
            }
        }
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("releaseKey") {
            val keystore = System.getenv("KAIRO_RELEASE_KEYSTORE")
            if (!keystore.isNullOrBlank()) {
                storeFile = file(keystore)
                storePassword = System.getenv("KAIRO_RELEASE_PASSWORD")
                keyAlias = "kairo98-beta"
                keyPassword = System.getenv("KAIRO_RELEASE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("releaseKey")
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets.getByName("main").assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/staging-assets")))
    sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/debug-assets"))
    sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("generated/release-assets"))
}

// Public APK and Play bundle use identical reviewed catalog metadata. Keep the
// private artwork/description inputs available to local debug builds only.
val debugAssets by tasks.registering(Sync::class) {
    from("src/main/assets")
    into(layout.buildDirectory.dir("generated/debug-assets"))
}
val releaseAssets by tasks.registering(Sync::class) {
    from("src/main/assets") { exclude("art/**", "catalog/dos/**") }
    from(zipTree(rootProject.file("catalog/online-v1.zip"))) { into("catalog/dos") }
    into(layout.buildDirectory.dir("generated/release-assets"))
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(debugAssets) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(releaseAssets) }

val stagingResources by tasks.registering(Sync::class) {
    from(rootProject.file("third_party/dosbox-staging/resources"))
    into(layout.buildDirectory.dir("generated/staging-assets/staging-resources"))
    exclude("meson.build")
}
tasks.named("preBuild") { dependsOn(stagingResources) }

dependencies {
    implementation(project(":frontend"))
    implementation(project(":backend-dos"))
}
