plugins {
    id("com.android.application")
}

android {
    namespace = "se.minapp.filmtajm"
    compileSdk = 37

    defaultConfig {
        applicationId = "se.minapp.filmtajm"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
