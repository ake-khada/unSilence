pluginManagement {
    buildscript {
        repositories {
            mavenCentral()
            maven {
                url = uri("https://storage.googleapis.com/r8-releases/raw")
            }
        }
        dependencies {
            classpath("com.android.tools:r8:8.13.19")
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "unsilence"
include(":app")
