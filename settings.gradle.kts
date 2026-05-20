pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketSecureShell"

include(":app")
include(":terminal-emulator")
include(":terminal-view")

// vendor/termux-app (pinned to v0.118.3) ships build.gradle scripts written for an older
// AGP/Gradle: they call getDefaultProguardFile("proguard-android.txt") (rejected by AGP 9+)
// and publish via afterEvaluate { from components.release } (no release component is
// registered without singleVariant). Instead of mutating the submodule, generate patched
// copies at configuration time and point each project's build file at the copy.
fun patchTermuxBuildScript(source: String): String {
    val out = StringBuilder()
    val lines = source.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.contains("apply plugin: 'maven-publish'") -> i++
            line.startsWith("task sourceJar") || line.startsWith("afterEvaluate") -> {
                i++
                while (i < lines.size && !lines[i].startsWith("}")) i++
                i++
            }
            else -> {
                out.appendLine(line.replace("proguard-android.txt", "proguard-android-optimize.txt"))
                i++
            }
        }
    }
    return out.toString()
}

listOf("terminal-emulator", "terminal-view").forEach { name ->
    val vendorDir = file("vendor/termux-app/$name")
    val patched = file("build/vendor-patched/$name/build.gradle")
    patched.parentFile.mkdirs()
    patched.writeText(patchTermuxBuildScript(file("$vendorDir/build.gradle").readText()))
    project(":$name").projectDir = vendorDir
    project(":$name").buildFileName = patched.relativeTo(vendorDir).path
}
