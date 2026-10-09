import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.vanniktech.maven.publish")
}

description = "Revnix SDK for Android: Play Billing 8 purchases, entitlements, paywalls and attribution."

mavenPublishing {
    // AGP's bundled Dokka cannot read Java 17 sealed classes (PermittedSubclasses requires ASM9).
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = false))
}

val emptyJavadocJar by tasks.registering(Jar::class) { archiveClassifier.set("javadoc") }
publishing.publications.withType<MavenPublication>().configureEach { artifact(emptyJavadocJar) }

android {
    namespace = "com.revnix.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":revnix-core"))
    // Play Billing 8 — the mandatory line for the Aug/Nov 2026 deadlines.
    api("com.android.billingclient:billing-ktx:8.3.0")
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("com.google.android.play:integrity:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
