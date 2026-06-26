import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "1.9.24"
    id("org.jetbrains.compose")  version "1.6.11"
}

group = "com.hackathon"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

kotlin {
    jvm("desktop") {
        jvmToolchain(21)
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    wasmJs {
        moduleName = "fruitMatch"
        browser {
            commonWebpackConfig {
                outputFileName = "fruitMatch.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.animation)
                implementation(compose.material3)
                implementation(compose.components.resources)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        // wasmJsMain has no extra deps — JS interop is stdlib
    }
}

compose.resources {
    packageOfResClass = "com.hackathon"
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "FruitMatch"
            packageVersion = "1.0.0"
        }
    }
}
