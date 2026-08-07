plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvmToolchain(17)
    explicitApi()

    jvm()
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            // Ktor: the forked transport. revnix-core keeps OkHttp so the
            // shipped revnix-android config stays source-compatible.
            implementation("io.ktor:ktor-client-core:3.0.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation("io.ktor:ktor-client-mock:3.0.3")
        }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.0.3") }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.0.3") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.0.3") }
    }
}

android {
    namespace = "com.revnix.kmp"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "skipped", "failed") }
}
