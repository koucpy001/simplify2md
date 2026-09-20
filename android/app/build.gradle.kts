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

// --- Release signing + version injection (plan todo 21) ---
// Signing material arrives ONLY via environment variables (GitHub Secrets in CI;
// the workflow decodes ANDROID_KEYSTORE_BASE64 to a temp file and points
// ANDROID_KEYSTORE_PATH at it). The four secret NAMES are:
//   ANDROID_KEYSTORE_PATH / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD
// No keystore or password is ever committed or printed.
//
// Signing decision (hard-coded, review D1):
//   - material absent + branch/PR build  -> skip signing, print an explicit
//     warning, produce an UNSIGNED artifact;
//   - material absent + -PrequireSigning=true -> throw and fail the build;
//     an unsigned release artifact must never be produced in that case.
val envVars = System.getenv()
val keystorePath = envVars["ANDROID_KEYSTORE_PATH"]?.trim().orEmpty()
val keystorePassword = envVars["ANDROID_KEYSTORE_PASSWORD"]?.trim().orEmpty()
val keyAlias = envVars["ANDROID_KEY_ALIAS"]?.trim().orEmpty()
val keyPassword = envVars["ANDROID_KEY_PASSWORD"]?.trim().orEmpty()
val signingMaterialPresent =
    keystorePath.isNotBlank() && keystorePassword.isNotBlank() &&
        keyAlias.isNotBlank() && keyPassword.isNotBlank()
val requireSigning = providers.gradleProperty("requireSigning").orNull == "true"

if (requireSigning && !signingMaterialPresent) {
    throw GradleException(
        "Signing was explicitly requested (-PrequireSigning=true) but the signing " +
            "material is missing: ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, " +
            "ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD must all be set. " +
            "Refusing to produce an unsigned release artifact."
    )
} else if (!signingMaterialPresent) {
    println(
        "WARNING: signing material not configured (ANDROID_KEYSTORE_PATH / " +
            "ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD); " +
            "the release APK will be UNSIGNED."
    )
}

// Version injection (review D2): tag-ness is decided BEFORE any parsing.
// Version parsing happens only when -PappVersionName is explicitly passed OR
// GITHUB_REF_TYPE == 'tag'. In every other case (branch/PR, where
// GITHUB_REF_NAME is a branch name such as 'main') we fall back to the todo 1
// placeholder WITHOUT parsing, so a branch name can never be mistaken for a
// version and fail android-ci.
//
// versionCode derivation (deterministic from the tag):
//   versionCode = major*1_000_000 + minor*10_000 + patch*100 + channel
//   channel: 99 for a stable release; for a prerelease, the trailing integer of
//   the suffix (-rc1 -> 1, -beta.2 -> 2), constrained to 1..98 so -rc99 can
//   never collide with the stable channel 99. A suffix without a trailing
//   integer (e.g. -alpha) fails. The numeric core must be exactly three
//   segments major.minor.patch; major <= 2099, minor/patch <= 99. This keeps
//   the stable release strictly above all of its prereleases
//   (v0.3.0 = 30099 > v0.3.0-rc2 = 30002) so upgrades install in order.
val PLACEHOLDER_VERSION_NAME = "0.0.0"
val PLACEHOLDER_VERSION_CODE = 1

fun parseAppVersion(tag: String): Pair<String, Int> {
    val trimmed = tag.trim()
    // Strip a single leading lowercase 'v' prefix; anything else (e.g. uppercase
    // 'V0.3.0') is left as-is and fails the core parse below, explicitly.
    val name = if (trimmed.startsWith("v")) trimmed.substring(1) else trimmed
    val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(.+))?$").matchEntire(name)
        ?: throw GradleException(
            "Invalid app version '$tag': expected exactly 'v<major>.<minor>.<patch>' " +
                "optionally followed by a '-<suffix>' prerelease (e.g. v0.3.0-rc1)."
        )
    val (majorStr, minorStr, patchStr, suffix) = match.destructured
    val major = majorStr.toIntOrNull() ?: throw GradleException("Invalid major '$majorStr' in '$tag'.")
    val minor = minorStr.toIntOrNull() ?: throw GradleException("Invalid minor '$minorStr' in '$tag'.")
    val patch = patchStr.toIntOrNull() ?: throw GradleException("Invalid patch '$patchStr' in '$tag'.")
    if (major > 2099) throw GradleException("Major version $major exceeds the maximum of 2099 ('$tag').")
    if (minor > 99) throw GradleException("Minor version $minor exceeds the maximum of 99 ('$tag').")
    if (patch > 99) throw GradleException("Patch version $patch exceeds the maximum of 99 ('$tag').")
    val channel = if (suffix.isEmpty()) {
        99 // stable release channel (highest)
    } else {
        val trailing = Regex("(\\d+)$").find(suffix)?.groupValues?.last()?.toIntOrNull()
            ?: throw GradleException(
                "Prerelease suffix '-$suffix' in '$tag' has no trailing integer " +
                    "(e.g. -rc1, -beta.2); a bare suffix like -alpha is not allowed."
            )
        if (trailing < 1 || trailing > 98) {
            throw GradleException(
                "Prerelease channel $trailing from '-$suffix' in '$tag' is out of range 1..98 " +
                    "(0 and >=99 would collide with the stable channel 99)."
            )
        }
        trailing
    }
    return name to (major * 1_000_000 + minor * 10_000 + patch * 100 + channel)
}

val explicitVersionName = providers.gradleProperty("appVersionName").orNull?.trim().orEmpty()
val githubRefType = envVars["GITHUB_REF_TYPE"]?.trim().orEmpty()
val githubRefName = envVars["GITHUB_REF_NAME"]?.trim().orEmpty()

val derivedVersion: Pair<String, Int> = when {
    explicitVersionName.isNotBlank() -> parseAppVersion(explicitVersionName)
    githubRefType == "tag" -> parseAppVersion(githubRefName) // blank GITHUB_REF_NAME fails explicitly
    else -> {
        println(
            "INFO: no -PappVersionName and GITHUB_REF_TYPE != 'tag' " +
                "(GITHUB_REF_NAME='$githubRefName' treated as a branch name, not parsed); " +
                "using placeholder version $PLACEHOLDER_VERSION_NAME ($PLACEHOLDER_VERSION_CODE)."
        )
        PLACEHOLDER_VERSION_NAME to PLACEHOLDER_VERSION_CODE
    }
}

// Matrix helper (verification only): exercises parseAppVersion over a list of
// inputs in ONE Gradle run, printing derived values or the explicit failure.
tasks.register("printVersionMatrix") {
    group = "verification"
    description = "Print derived versionName/versionCode for a list of tag inputs (todo 21 evidence)"
    doLast {
        println("BUILD versionName=${derivedVersion.first} versionCode=${derivedVersion.second}")
        val cases = listOf(
            "v0.3.0", "v0.3.0-rc2", "v1.2.3-beta.2",
            "v1.2", "v1.2.3.4", "v1.2.3-alpha", "v1.2.3-rc0", "v1.2.3-rc99",
            "v2100.0.0", "v1.100.0",
            // adversarial probes
            "v0.0.0", "V0.3.0", "v0.3.0+build", " v0.3.0 ", "v0.3.0-rc1-hotfix2", "v1.2.3-",
        )
        for (input in cases) {
            try {
                val (n, code) = parseAppVersion(input)
                println("MATRIX '$input' -> versionName=$n versionCode=$code")
            } catch (e: GradleException) {
                println("MATRIX '$input' -> FAIL: ${e.message}")
            }
        }
        val stable = parseAppVersion("v0.3.0").second
        val rc2 = parseAppVersion("v0.3.0-rc2").second
        println("ORDERING: v0.3.0=$stable > v0.3.0-rc2=$rc2 -> ${stable > rc2}")
    }
}

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
        // Placeholders are overridden by the tag-derived values above (todo 21).
        versionCode = derivedVersion.second
        versionName = derivedVersion.first
    }

    signingConfigs {
        if (signingMaterialPresent) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAlias
                keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingMaterialPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            // else: unsigned release APK (explicit warning printed above), unless
            // -PrequireSigning=true already failed the build.
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
