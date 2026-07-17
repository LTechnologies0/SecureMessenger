plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    id("org.jetbrains.dokka") version "2.2.0"
}

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.squareup.wire:wire-gradle-plugin:${libs.versions.wire.get()}") {
            exclude(group = "com.squareup.wire", module = "wire-swift-generator")
            exclude(group = "com.squareup.wire", module = "wire-grpc-client")
            exclude(group = "com.squareup.wire", module = "wire-grpc-jvm")
            exclude(group = "com.squareup.wire", module = "wire-grpc-server-generator")
            exclude(group = "io.outfoxx", module = "swiftpoet")
        }
        classpath(files("vendor/signal/wire-handler/wire-handler-1.0.0.jar"))
    }
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
    dokka(project(":protocol:signal"))
    dokka(project(":vendor:signal:libsignal-service"))
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
