pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.highcapable.gropify") version "1.0.2"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
    }
}

fun runGitCommand(vararg args: String): String? {
    val exec = providers.exec {
        commandLine(listOf("git") + args)
        workingDir = settingsDir
        isIgnoreExitValue = true
    }
    val output = exec.standardOutput.asText.get().trim()
    return if (exec.result.get().exitValue == 0 && output.isNotBlank()) output else null
}

val gitHash = runGitCommand("rev-parse", "--short", "HEAD") ?: "unknown"
val gitBranch = run {
    val url = runGitCommand("remote", "get-url", "origin")
        ?: "https://github.com/killerprojecte/SoundMan.git"
    val branch = runGitCommand("branch", "--show-current") ?: "master"
    val repoPath =
        """github\.com[:/](.+?)(\.git)?$""".toRegex().find(url)?.groupValues?.get(1).orEmpty()
    if (repoPath.isBlank()) branch else "$repoPath/$branch"
}
val gitVersionCode =
    (runGitCommand("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0).coerceAtLeast(1)
val buildSuffix = providers.gradleProperty("buildSuffix").orNull ?: "dev"

gradle.extra["gitHash"] = gitHash
gradle.extra["gitBranch"] = gitBranch
gradle.extra["gitVersionCode"] = gitVersionCode
gradle.extra["buildSuffix"] = buildSuffix
gradle.extra["versionSuffix"] = "-$gitHash-r$gitVersionCode-$buildSuffix"

gropify {
    rootProject {
        common {
            isEnabled = false
        }
    }

    projects(":app") {
        android {
            isEnabled = true

            permanentKeyValues(
                "git.hash" to gitHash,
                "git.branch" to gitBranch,
                "build.number" to gitVersionCode,
                "build.channel" to buildSuffix,
            )
        }
    }
}

rootProject.name = "SoundMan"
include(":app")
