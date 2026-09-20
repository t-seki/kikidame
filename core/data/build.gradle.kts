plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}
android {
    namespace = "dev.tseki.kikidame.data"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // マイグレーションテストが過去のスキーマ JSON を読めるように
    sourceSets {
        getByName("test") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
room {
    schemaDirectory("$projectDir/schemas")
}
dependencies {
    api(project(":core:domain"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jellyfin.core)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)
    // 統合テスト（#97）が SDK を本物のまま動かすので、app と同じ slf4j バインディングをテストにも
    testRuntimeOnly(libs.slf4j.android)
}

// 統合テスト（#97）: 環境変数はテストタスクの入力に数えられないので、KIKIDAME_JELLYFIN_URL が付いているときは
// 毎回走らせる（サーバの中身が変わっても up-to-date で飛ばされないように）。付いていなければ今までどおりキャッシュが効く
tasks.withType<Test>().configureEach {
    val jellyfinUrl = System.getenv("KIKIDAME_JELLYFIN_URL") ?: ""
    inputs.property("kikidameJellyfinUrl", jellyfinUrl)
    outputs.upToDateWhen { jellyfinUrl.isEmpty() }
}
