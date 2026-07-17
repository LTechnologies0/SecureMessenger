pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
            content {
                includeGroup("org.signal")
            }
        }
    }
}

rootProject.name = "secure-messenger"

include(
    ":app",
    ":core:model",
    ":core:proxy",
    ":core:network",
    ":core:security",
    ":data",
    ":protocol:api",
    ":protocol:xmpp",
    ":protocol:matrix",
    ":protocol:telegram",
    ":protocol:signal",
    ":vendor:signal:util-jvm",
    ":vendor:signal:models-jvm",
    ":vendor:signal:network-jvm",
    ":vendor:signal:libsignal-service",
)
