plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = rootProject.file("shared/gradle/android-module.gradle"))

android {
    androidResources { noCompress += listOf("json", "idx") }
    namespace = "com.mrjackspade.kairodos"

    defaultConfig {
        applicationId = "com.loxifi.kairodos"
        versionCode = providers.gradleProperty("kairodosVersionCode").orNull?.toInt() ?: 911
        versionName = providers.gradleProperty("kairodosVersionName").orNull ?: "0.9.11"
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
        create("withImagesRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets.getByName("main").assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/staging-assets")))
    sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/release-assets"))
    sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("generated/release-assets"))
    sourceSets.getByName("withImagesRelease").assets.srcDirs(
        layout.buildDirectory.dir("generated/release-assets"),
        layout.buildDirectory.dir("generated/release-artwork"))
}

// Same executable and core metadata for every distribution. Play omits the
// additional artwork catalog; the image-inclusive GitHub APK adds image payloads.
val catalogAssets by tasks.registering(Sync::class) {
    from("src/main/assets") { exclude("catalog/**", "art/**") }
    from(rootProject.file("catalog/parts")) {
        into("catalog")
        if (providers.gradleProperty("kairoDistribution").orNull == "play") exclude("art.nsfw.*")
    }
    into(layout.buildDirectory.dir("generated/release-assets"))
}
val releaseArtwork by tasks.registering(Sync::class) {
    from(rootProject.file("catalog/artwork")) { include("art/**") }
    into(layout.buildDirectory.dir("generated/release-artwork"))
}
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(catalogAssets)
}
tasks.matching { it.name == "preWithImagesReleaseBuild" }.configureEach { dependsOn(releaseArtwork) }

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
