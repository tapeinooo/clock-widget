plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.clockwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.clockwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.8"
        // 更新の確認に使う、このアプリを作ったときのコミット（GitHub Actions が渡す）
        buildConfigField("String", "GIT_SHA", "\"${providers.gradleProperty("gitSha").orElse("dev").get()}\"")
    }

    signingConfigs {
        // 自分用の固定の鍵（どの環境でビルドしても同じ署名になり、上書きで入れられる）
        create("mine") {
            storeFile = rootProject.file("clock.p12")
            storeType = "pkcs12"
            storePassword = "clockwidget"
            keyAlias = "clock"
            keyPassword = "clockwidget"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 自分のスマホに入れるだけなので、固定の鍵で署名する
            signingConfig = signingConfigs.getByName("mine")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
