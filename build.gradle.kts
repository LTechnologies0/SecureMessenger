plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    id("org.jetbrains.dokka") version "2.2.0"
}

subprojects {
    pluginManager.withPlugin("com.android.library") {
        apply(plugin = "org.jetbrains.dokka")
    }
    pluginManager.withPlugin("com.android.application") {
        apply(plugin = "org.jetbrains.dokka")
    }
}

dependencies {
    dokka(project(":app"))
    dokka(project(":core:model"))
    dokka(project(":core:proxy"))
    dokka(project(":core:network"))
    dokka(project(":core:security"))
    dokka(project(":data"))
    dokka(project(":protocol:api"))
    dokka(project(":protocol:xmpp"))
    dokka(project(":protocol:matrix"))
    dokka(project(":protocol:telegram"))
    dokka(project(":protocol:discord"))
    dokka(project(":protocol:signal"))
}

dokka {
    dokkaPublications.html {
        moduleName.set("SecureMessenger")
        moduleVersion.set(
            providers.gradleProperty("VERSION_NAME").orElse("1.0.0"),
        )
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}
