plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries)
}
android {
    namespace = "dev.tseki.kikidame"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.tseki.kikidame"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }
    // release の署名（#94）。鍵とパスフレーズは repo に置かず環境変数から読む。
    // 無ければ署名設定を付けずにビルドする（CI の test と、鍵を持たない人の assembleRelease を通すため）。
    // KIKIDAME_KEYSTORE=/path/to/kikidame-upload.jks KIKIDAME_KEYSTORE_PASSWORD=… KIKIDAME_KEY_ALIAS=kikidame KIKIDAME_KEY_PASSWORD=…
    val keystorePath = System.getenv("KIKIDAME_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KIKIDAME_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KIKIDAME_KEY_ALIAS") ?: "kikidame"
                keyPassword = System.getenv("KIKIDAME_KEY_PASSWORD") ?: System.getenv("KIKIDAME_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // OS の「アプリの言語」（Android 13+）に載せる locales_config.xml を、res/values-* と resources.properties から生成する（#104、ADR 0009）
        generateLocaleConfig = true
    }
    lint {
        // 文言は values/（英語、既定）と values-ja/ の両方に置く。片方だけの追加をビルドで落とす
        error += "MissingTranslation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric を使わないプレーンな JVM テストでも android.util.Log を呼べるようにする
        unitTests.isReturnDefaultValues = true
    }
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    ksp(libs.androidx.hilt.compiler)

    // jellyfin-sdk-kotlin は kotlin-logging 経由で slf4j を要求する。バインディングが無いと実行時に落ちる
    runtimeOnly(libs.slf4j.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.work.testing)
}

// 依存ライブラリのライセンス一覧（設定 > オープンソースライセンス）。ビルド時には生成せず、
// `./gradlew :app:exportLibraryDefinitions` で src/main/res/raw/aboutlibraries.json を書き出してコミットする。
// F-Droid や CI のオフラインビルドでも同じ一覧になり、依存を変えたときは再生成して差分を見る
aboutLibraries {
    // jellyfin-sdk-kotlin の POM は LGPL-3.0 を名前と URL でしか宣言しておらず本文が取れないので、config/licenses で本文を補う
    collect {
        configPath = file("config")
    }
    export {
        outputFile = file("src/main/res/raw/aboutlibraries.json")
        variant = "release"
        prettyPrint = true
    }
    license {
        // LGPL-3.0 §4(b) は GPL 本文も添えることを求めるので、直接使う依存が無くても GPL-3.0 を同梱する
        additionalLicenses.add("GPL-3.0-only")
    }
}
