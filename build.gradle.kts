import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.1.20"
    id("com.android.library") version "8.10.1"
    `maven-publish`
}

// JitPack requires the group to be com.github.<username>.
group = "com.github.zaiddev1996"
version = "0.2.0"

val ktorVersion = "3.1.1"
val coroutinesVersion = "1.10.1"

kotlin {
    explicitApi()

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("io.ktor:ktor-client-core:$ktorVersion")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:$ktorVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:$ktorVersion") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:$ktorVersion") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:$ktorVersion") }
    }
}

android {
    namespace = "dev.reelkit"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Pick the simulator for iOS tests: ./gradlew iosSimulatorArm64Test -PiosSimulatorDevice="iPhone 17 Pro"
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("iosSimulatorDevice").orNull?.let { device.set(it) }
    // Standalone mode never boots the simulator, so trustd is missing and every TLS handshake fails.
    // Only real-network tests need a booted device: -PiosBootedSimulator=true (boot it first with simctl).
    if (providers.gradleProperty("iosBootedSimulator").orNull == "true") standalone.set(false)
}

tasks.withType<Test>().configureEach {
    if (name == "jvmTest") {
        (this as Test).useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("insta-media-downloader")
            description.set("Resolve and download public Instagram reels on Android and iOS (Kotlin Multiplatform), with remotely configurable parameters.")
            licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
        }
    }
}
