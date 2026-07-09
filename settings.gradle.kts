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
    ":protocol:discord",
    ":protocol:signal",
)
