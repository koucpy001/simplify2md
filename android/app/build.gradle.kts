// :app module — lightweight native WebView shell for the simplify2md Android port.
// Keep the dependency surface minimal: no Compose, Room, WorkManager, DataStore or Hilt.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// --- Frontend asset staging (plan todo 3) ---
// The Vue frontend lives outside this Gradle build (mdview/frontend). This task
// builds it with `--mode android` (the hard requirement: the default mode resolves
// the desktop bridge and produces an APK that fails at runtime) and mirrors
// mdview/frontend/dist/** into app/src/main/assets/frontend/**, which the
// WebViewAssetLoader host (todo 2) serves at https://appassets.androidplatform.net/.
//
// Incrementality: inputs are the frontend sources + lockfile + configs; the output
// is the staged asset tree. When nothing changed the task is UP-TO-DATE and neither
// npm ci nor the build runs.
//
// Failure semantics: npm ci and the build run before the sync; a non-zero exit
// throws and fails the Gradle task, so a broken frontend build can never leave
// stale assets in the APK (the plan's explicit failure scenario). The sync itself
// mirrors dist into the staged dir, deleting stale files.
val frontendDir = rootProject.projectDir.parentFile.resolve("mdview/frontend")
val frontendDistDir = frontendDir.resolve("dist")
val stagedAssetsDir = project.layout.projectDirectory.dir("src/main/assets/frontend")
val npmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val stageFrontendAssets = tasks.register("stageFrontendAssets") {
    group = "build"
    description = "Build the Vue frontend with --mode android and stage dist into APK assets"

    inputs.files(
        frontendDir.resolve("package.json"),
        frontendDir.resolve("package-lock.json"),
        frontendDir.resolve("index.html"),
        frontendDir.resolve("vite.config.ts"),
        frontendDir.resolve("tsconfig.json"),
        frontendDir.resolve("tsconfig.node.json"),
    )
    inputs.dir(frontendDir.resolve("src"))
    outputs.dir(stagedAssetsDir)

    doLast {
        providers.exec {
            workingDir(frontendDir)
            commandLine(npmCommand, "ci")
        }.result.get().assertNormalExitValue()
        providers.exec {
            workingDir(frontendDir)
            commandLine(npmCommand, "run", "build", "--", "--mode", "android")
        }.result.get().assertNormalExitValue()
        sync {
            from(frontendDistDir)
            into(stagedAssetsDir)
        }
    }
}

android {
    namespace = "io.github.koucpy001.simplify2md"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.koucpy001.simplify2md"
        minSdk = 24
        targetSdk = 36
        // Placeholders only — final versionCode/versionName are injected from the
        // release tag in todo 21.
        versionCode = 1
        versionName = "0.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Java 21 bytecode target. CI runs Gradle on Temurin 21 (todo 4); no Gradle
    // Java toolchain is declared so configuration does not require a local JDK 21.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

// Both assembleDebug and assembleRelease must depend on the staging task so a
// plain `./gradlew :app:assembleRelease` produces an APK containing the frontend.
tasks.named("preBuild") {
    dependsOn(stageFrontendAssets)
}
