import java.util.Properties
plugins {
    id("com.android.application")
}

val releaseSigning = Properties().apply {
    val localSigning = rootProject.file("signing.properties")
    if (localSigning.exists()) localSigning.inputStream().use { load(it) }
}
android {
    namespace = "com.antigravity.skipapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.antigravity.skipapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 20
        versionName = "1.1.9"




        // 严格限定只构建 arm64-v8a 架构（64 位主流手机），排除其他任何架构
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/skipapp.keystore")
            storePassword = System.getenv("SKIPAPP_STORE_PASSWORD") ?: releaseSigning.getProperty("storePassword")
            keyAlias = "skipapp"
            keyPassword = System.getenv("SKIPAPP_KEY_PASSWORD") ?: releaseSigning.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            excludes += setOf(
                "lib/armeabi/**",
                "lib/armeabi-v7a/**",
                "lib/x86/**",
                "lib/x86_64/**",
                "lib/mips*/**",
                "lib/riscv64/**"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    // 纯原生 Android SDK 实现，零外部依赖，100% 离线脱网构建
}
