import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose.hot-reload") version "1.0.0"
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

    // JCEF — Chromium embedded browser (auto-downloads native binaries)
    implementation("me.friwi:jcefmaven:122.1.10")

    // pjsip JNI bindings
    implementation(files("libs/pjsua2.jar"))

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

// Hot reload JVM args (hotRun doesn't inherit compose.desktop.application.jvmArgs)
tasks.matching { it.name.startsWith("hotRun") || it.name.startsWith("hotDev") }.configureEach {
    if (this is JavaExec) {
        jvmArgs("-Dpjsip.library.path=${projectDir}/libs")
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    }
}

compose.desktop {
    application {
        mainClass = "uz.yalla.sipphone.MainKt"

        jvmArgs += "-Dpjsip.library.path=${projectDir}/libs"

        // Force OpenGL on Windows only (Direct3D crashes with JCEF SwingPanel)
        if (System.getProperty("os.name").lowercase().contains("win")) {
            jvmArgs += "-Dskiko.renderApi=OPENGL"
        }

        // Required for jcefmaven on macOS with JDK 16+
        jvmArgs += listOf(
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )

        nativeDistributions {
            modules("java.naming")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "YallaSipPhone"
            packageVersion = "1.0.0"
            vendor = "Ildam"
            description = "Yalla SIP Phone - Oktell Operator Softphone"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))

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
