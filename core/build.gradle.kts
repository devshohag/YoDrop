plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test-junit"))
        }
    }
}

android {
    namespace = "com.yodrop.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
}

tasks.named<Test>("jvmTest") {
    testLogging {
        events("started", "passed", "skipped", "failed", "standardOut", "standardError")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
