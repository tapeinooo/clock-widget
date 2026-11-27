plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版の番号は GitHub Actions の実行番号から自動で決める（共通ルール）。
// 手で番号を付けていた頃（v1.10・versionCode 11）より大きくするため、gradle.properties の versionOffset・versionMajor を使う
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
val versionOffset = (project.findProperty("versionOffset") as String?)?.toIntOrNull() ?: 0
val versionMajor = (project.findProperty("versionMajor") as String?)?.toIntOrNull() ?: 1
val appVersionName = "$versionMajor.0.$buildNumber"

// GitHub Actions がリリースのタグ（v＋versionName）を作るのに使う
tasks.register("printVersionName") { doLast { println(appVersionName) } }

android {
    namespace = "com.example.clockwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.clockwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber + versionOffset
        // GitHub のリリースのタグは「v」＋ versionName（アプリの更新はタグで新しい版を見分ける）
        versionName = appVersionName
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
            // 使っていない部品を削って小さくする
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
