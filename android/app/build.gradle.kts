import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 릴리스 서명 정보: 저장소 루트의 .keystore/(git 제외). 백업 위치는 README 참고.
val releaseSigningFile = rootProject.file("../.keystore/release-signing.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
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

    signingConfigs {
        if (releaseSigningFile.isFile) {
            create("release") {
                storeFile = releaseSigningFile.resolveSibling(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 서명 정보 없이 릴리스를 만들면 서명 안 된 APK가 조용히 나오므로 막는다.
val missingSigningMessage = "릴리스 서명 정보가 없습니다: ${releaseSigningFile.path}"
val hasReleaseSigning = releaseSigningFile.isFile
tasks.configureEach {
    if (name == "preReleaseBuild" && !hasReleaseSigning) {
        doFirst { throw GradleException(missingSigningMessage) }
    }
}

dependencies {
    testImplementation(libs.junit)
}
