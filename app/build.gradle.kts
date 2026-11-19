plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val repoRoot = rootProject.projectDir

val rustEngineSources = fileTree(repoRoot.resolve("engine-rs")) {
    include("**/*.rs", "**/Cargo.toml", "Cargo.toml", "Cargo.lock", "build-android.sh")
    exclude("**/target/**", "**/tests/**")
}

val buildRustEngine by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile Rust torrent engine into libengine.so for arm64-v8a"
    workingDir(repoRoot)
    commandLine("bash", repoRoot.resolve("engine-rs/build-android.sh").absolutePath)
    inputs.files(rustEngineSources)
    outputs.file(file("src/main/jniLibs/arm64-v8a/libengine.so"))
}

val emulatorAbi = providers.gradleProperty("cinito.emulator").map { it.toBoolean() }.getOrElse(false)

val buildRustEngineEmulator by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile Rust torrent engine into libengine.so for x86_64 emulator"
    workingDir(repoRoot)
    environment("ENGINE_ABI", "x86_64")
    commandLine("bash", repoRoot.resolve("engine-rs/build-android.sh").absolutePath)
    inputs.files(rustEngineSources)
    outputs.file(file("src/main/jniLibs/x86_64/libengine.so"))
}

tasks.named("preBuild") {
    dependsOn(buildRustEngine)
    if (emulatorAbi) dependsOn(buildRustEngineEmulator)
}

android {
    namespace = "org.cinito.online"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "org.cinito.online"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("APP_VERSION_CODE").map { it.toInt() }.getOrElse(1)
        versionName = providers.gradleProperty("APP_VERSION_NAME").getOrElse("0.1.3-alpha.1")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.add("arm64-v8a")
            if (emulatorAbi) abiFilters.add("x86_64")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "mmdb"
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(file("../webos-receiver/assets"))
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

buildRustEngine.configure {
    inputs.property("versionName", android.defaultConfig.versionName)
    environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
}
buildRustEngineEmulator.configure {
    inputs.property("versionName", android.defaultConfig.versionName)
    environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Media3 + NextLib (FFmpeg audio/video codecs + libass ASS/SSA subtitles)
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.18.0")

    // Networking, DoH, HTML/JSON parsing, and poster image loading
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-okhttp:3.1.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jsoup:jsoup:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
