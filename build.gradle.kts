plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("com.github.triplet.play") version "4.1.1" apply false
}

dependencyLocking {
    lockAllConfigurations()
}

// Regenerate all dependency lockfiles in one command:
//     ./gradlew writeLocks --write-locks
tasks.register("writeLocks") {
    dependsOn(":dependencies", ":app:dependencies")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "writeLocks must be run with --write-locks: ./gradlew writeLocks --write-locks"
        }
    }
}

// Set namespace for Termux submodule libraries (AGP 8.x requires namespace in build.gradle)
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            when (project.name) {
                "terminal-emulator" -> namespace = "com.termux.terminal"
                "terminal-view" -> namespace = "com.termux.view"
            }
        }
    }
}
