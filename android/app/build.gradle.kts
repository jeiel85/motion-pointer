plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.jeiel85.motionpointer"
    compileSdk { version = release(36) }

    defaultConfig {
        applicationId = "com.jeiel85.motionpointer"
        // BluetoothHidDevice(폰을 블루투스 마우스로 등록)는 API 28부터 있다.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
