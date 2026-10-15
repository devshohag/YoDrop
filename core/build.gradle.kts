plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val jvmAndAndroidMain by creating {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.bouncycastle.provider)
            }
        }
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)
        getByName("androidMain").dependsOn(jvmAndAndroidMain)

        getByName("jvmTest").kotlin.srcDir("src/x25519Test/kotlin")
        getByName("androidInstrumentedTest").kotlin.srcDir(
            "src/x25519Test/kotlin"
        )

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
        testInstrumentationRunner =
            "com.yodrop.core.crypto.X25519Instrumentation"
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
