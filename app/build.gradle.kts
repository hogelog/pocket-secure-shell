import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.github.triplet.play")
}

// Embedded into BuildConfig so the app can show a `<versionName>-<shortrev>` footer.
// Falls back to "unknown" when git is unavailable (e.g. source archive builds).
val gitShortRev: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Required environment variable $name is missing or empty.")

val prNumber: String? = System.getenv("PR_NUMBER")?.takeIf { it.isNotBlank() }
val releaseVersion: String? = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }
val releaseVersionSuffix: String? = System.getenv("RELEASE_VERSION_SUFFIX")?.takeIf { it.isNotBlank() }
val baseVersionName = "0.4.9"
val appVersionName: String = when {
    releaseVersion != null && releaseVersionSuffix != null -> "$releaseVersion-$releaseVersionSuffix"
    releaseVersion != null -> releaseVersion
    prNumber != null -> "$baseVersionName-pr-$prNumber-$gitShortRev"
    else -> baseVersionName
}

// Tag-driven monotonic versionCode: MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + COMMITS_SINCE_TAG.
// Allows up to 99 commits per patch; widening to MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + N
// later stays monotonic because every segment shifts to a higher decade.
// Release builds carry the tag in RELEASE_VERSION; PR builds fall back to baseVersionName so they
// share the same monotonic space as main builds instead of the local-only versionCode=1 sentinel.
val versionForCode: String? = when {
    releaseVersion != null -> releaseVersion
    prNumber != null -> baseVersionName
    else -> null
}

// COMMITS_SINCE_TAG counts commits on main only. PR builds count up to the merge-base with the PR
// base (BASE_SHA) instead of HEAD, so the PR's own commits never bump versionCode; otherwise builds
// across PRs get different codes and fail to install over each other as a downgrade. Release/main
// builds build a commit already on main, so they count HEAD. Outside CI (no BASE_SHA) PR builds
// fall back to HEAD, where versionCode is irrelevant (no installable artifact is published).
val countTip: String = if (prNumber != null) {
    val baseSha = System.getenv("BASE_SHA")?.takeIf { it.isNotBlank() }
    if (baseSha != null) {
        providers.exec {
            commandLine("git", "merge-base", "HEAD", baseSha)
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.getOrElse("").ifEmpty { "HEAD" }
    } else {
        "HEAD"
    }
} else {
    "HEAD"
}

val commitsSinceTag: Int = if (versionForCode != null) {
    val prevTag = providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match=v*", "--exclude=v$versionForCode", countTip)
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.getOrElse("")
    val countCmd = if (prevTag.isNotEmpty()) {
        listOf("git", "rev-list", "--count", "$prevTag..$countTip")
    } else {
        listOf("git", "rev-list", "--count", countTip)
    }
    providers.exec {
        commandLine(countCmd)
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 0 }.getOrElse(0)
} else {
    0
}

val appVersionCode: Int = if (versionForCode != null) {
    val parts = versionForCode.split(".")
    require(parts.size == 3) {
        "Version for versionCode must be MAJOR.MINOR.PATCH, got '$versionForCode'"
    }
    val (major, minor, patch) = parts.map {
        it.toIntOrNull() ?: error("Version segment '$it' is not an integer")
    }
    require(commitsSinceTag in 0..99) {
        "commitsSinceTag must be 0..99 to avoid colliding with the PATCH segment; got $commitsSinceTag"
    }
    major * 1_000_000 + minor * 10_000 + patch * 100 + commitsSinceTag
} else {
    1
}

// Lock only the classpaths that ship in the APK. Internal/metadata/test
// configurations resolve inconsistently between `:app:dependencies` and the
// real build, producing brittle lockfiles.
configurations.configureEach {
    if (isCanBeResolved && name.matches(Regex("(debug|release)(Compile|Runtime)Classpath"))) {
        resolutionStrategy.activateDependencyLocking()
    }
}

android {
    namespace = "org.hogel.pocketssh"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.hogel.pocketssh"
        minSdk = 34
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "GIT_SHORT_REV", "\"$gitShortRev\"")
    }

    // Committed debug keystore so APKs from different CI runners share a signing
    // identity and can be installed as updates over each other.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release signingConfig is wired up only when CI provides the keystore env vars,
        // so local `assembleDebug` keeps working without release credentials.
        val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = requireEnv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = requireEnv("RELEASE_KEY_ALIAS")
                keyPassword = requireEnv("RELEASE_KEY_PASSWORD")
            }
        } else if (releaseVersion != null) {
            error("RELEASE_VERSION is set but RELEASE_KEYSTORE_PATH is missing; release builds must be signed.")
        }
    }

    buildTypes {
        debug {
            // Lets debug and release variants coexist on the same device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            // Sentry is debug-only — release builds carry no telemetry SDK at all.
            // SENTRY_DSN is a public client-side identifier (kept in CI vars, not
            // secrets). CrashReporting in the debug source set reads these and
            // drives a manual SentryAndroid.init; auto-init is disabled in the
            // debug manifest so the configuration callback (beforeSend scrubbing,
            // attachment toggles, replay sample rates) is the single source of
            // truth. An empty DSN makes the init call a no-op.
            buildConfigField("String", "SENTRY_DSN", "\"${System.getenv("SENTRY_DSN") ?: ""}\"")
            buildConfigField(
                "String",
                "SENTRY_RELEASE",
                "\"org.hogel.pocketssh@$appVersionName+$appVersionCode\"",
            )
            buildConfigField("String", "SENTRY_DIST", "\"$gitShortRev\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

// sshlib's fat jar bundles error_prone_annotations, conflicting with the
// transitive dependency.
configurations.configureEach {
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
}

play {
    track.set("internal")
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)

    // google-github-actions/auth writes the WIF credentials file and exposes its
    // path via GOOGLE_APPLICATION_CREDENTIALS. GoogleCredentials.fromStream (used
    // by GPP) accepts both service account keys and external_account (WIF) JSON.
    System.getenv("GOOGLE_APPLICATION_CREDENTIALS")?.takeIf { it.isNotBlank() }?.let {
        serviceAccountCredentials.set(file(it))
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation("org.connectbot:sshlib:2.2.48")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    // Sentry is debug-only — release builds ship without the SDK so the app
    // makes no telemetry phone-home in production.
    debugImplementation("io.sentry:sentry-android:8.43.1")

    // Pinned to stabilize dependency locking: AGP's data binding transforms
    // resolve kotlin-stdlib-common at build time, but `--write-locks` doesn't
    // capture it, producing a lock mismatch on CI. Keep this version aligned
    // with the Kotlin bundled by AGP — bumping AGP requires re-pinning here.
    // (In Kotlin 2.0+ this artifact is an empty KMP metadata jar on the JVM,
    // so pinning has no runtime cost.)
    implementation("org.jetbrains.kotlin:kotlin-stdlib-common:2.3.21")

    testImplementation("junit:junit:4.13.2")
}
