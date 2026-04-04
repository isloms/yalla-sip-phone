import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "uz.yalla.sipphone"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")

    // Serialization (Decompose screen configs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Navigation
    implementation("com.arkivanov.decompose:decompose:3.4.0")
    implementation("com.arkivanov.decompose:extensions-compose:3.4.0")
    implementation("com.arkivanov.essenty:lifecycle-coroutines:2.5.0")

    // DI
    implementation("io.insert-koin:koin-core:4.1.1")

    // Design system
    implementation("com.materialkolor:material-kolor:2.0.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // Settings persistence
    implementation("com.russhwolf:multiplatform-settings-no-arg:1.3.0")

    // pjsip JNI bindings
    implementation(files("libs/pjsua2.jar"))

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

compose.desktop {
    application {
        mainClass = "uz.yalla.sipphone.MainKt"

        jvmArgs += "-Dpjsip.library.path=${projectDir}/libs"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "YallaSipPhone"
            packageVersion = "1.0.0"
            vendor = "Ildam"
            description = "Yalla SIP Phone - Oktell Operator Softphone"

            macOS {
                bundleID = "uz.yalla.sipphone"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>YallaSipPhone needs microphone access for VoIP calls</string>
                    """.trimIndent()
                }
                entitlementsFile.set(project.file("src/main/resources/entitlements.plist"))
            }
        }
    }
}
