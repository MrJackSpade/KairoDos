plugins {
    id("com.android.library")
}

apply(from = rootProject.file("shared/gradle/android-module.gradle"))

android {
    namespace = "com.mrjackspade.kairodos.backend"

    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("../third_party/dosbox-pure/jni/Android.mk")
        }
    }
}
