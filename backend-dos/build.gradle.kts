plugins {
    id("com.android.library")
}

android {
    namespace = "com.mrjackspade.kairodos.backend"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
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
