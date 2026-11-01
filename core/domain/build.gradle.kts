plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
    alias(libs.plugins.kotlinter)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
dependencies {
    api(libs.kotlinx.datetime)
    api(libs.kotlinx.coroutines.core)
    // シグネチャの 31 は minSdk（app/build.gradle.kts と core/data/build.gradle.kts）に合わせて上げる。@signature の拡張子は catalog で表せないので、ここで足す
    signature(libs.android.api.signature.get().let { "${it.module}:${it.versionConstraint.requiredVersion}@signature" })
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test {
    useJUnitPlatform()
}
animalsniffer {
    // Kotlin / kotlinx は APK に同梱されるので、Android の API シグネチャに無くてよい
    ignore("kotlin", "kotlinx", "org.jetbrains")
}
tasks.named("animalsnifferTest") { enabled = false } // テストは JVM で動くので Android の API シグネチャで縛らない
