plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
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
    signature("net.sf.androidscents.signature:android-api-level-31:12_r1@signature")
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
