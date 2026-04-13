import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    // Hot Reload disabled — causes NoClassDefFoundError for lambda classes after recompile,
    // leading to cascading crashes (SettingsPopover$1$4, MainKt$main$3$2$1$1 → CEF SIGSEGV)
    // id("org.jetbrains.compose.hot-reload") version "1.0.0"
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

    // HTTP client
    val ktorVersion = "3.1.2"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // JCEF — Chromium embedded browser (auto-downloads native binaries)
    implementation("me.friwi:jcefmaven:122.1.10")

    // pjsip JNI bindings
    implementation(files("libs/pjsua2.jar"))

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("io.ktor:ktor-client-mock:3.1.2")
}

// Dev mode pjsip path (compose.desktop.application.jvmArgs handles --add-opens)
tasks.matching { it.name == "run" }.configureEach {
    if (this is JavaExec) {
        jvmArgs("-Dpjsip.library.path=${projectDir}/libs")
    }
}

tasks.register<JavaExec>("runDemo") {
    group = "demo"
    description = "Run visual demo with fake SIP engines simulating a busy operator day"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("uz.yalla.sipphone.demo.DemoMainKt")
    jvmArgs(
        "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
}

compose.desktop {
    application {
        mainClass = "uz.yalla.sipphone.MainKt"

        // JCEF needs --add-opens for sun.awt on macOS (sun.lwawt.macosx doesn't exist on Windows)
        val isMacOs = System.getProperty("os.name").lowercase().contains("mac")
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        if (isMacOs) {
            jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
            jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }

        nativeDistributions {
            includeAllModules = true
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

            windows {
                // Pinned UpgradeCode — NEVER change. Without it, every install is
                // side-by-side instead of an upgrade (spec §5 invariant I19).
                upgradeUuid = "E7A4F1B2-9C5D-4E8A-B1F6-2D3E4F5A6B7C"
                // Per-user install to %LOCALAPPDATA%\YallaSipPhone — no admin / UAC (spec Q1).
                perUserInstall = true
                menuGroup = "Yalla"
                shortcut = true
                menu = true
            }
        }
    }
}

// Fix JCEF helper executable permissions after packaging (macOS strips +x from app-resources)
tasks.matching { it.name == "createDistributable" || it.name == "packageDmg" }.configureEach {
    doLast {
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        appDir.walkTopDown()
            .filter { it.name.startsWith("jcef Helper") && it.isFile && it.parentFile.name == "MacOS" }
            .forEach { helper ->
                helper.setExecutable(true)
                logger.lifecycle("Fixed +x permission: ${helper.absolutePath}")
            }
    }
}
