plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("com.squareup.wire")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

wire {
    protoLibrary = true
    kotlin {
        javaInterop = true
    }
    sourcePath {
        srcDir("src/main/protowire")
    }
    custom {
        schemaHandlerFactoryClass = "org.signal.wire.Factory"
    }
}

dependencies {
    api(libs.google.libphonenumber)
    api(libs.jackson.databind)
    api(libs.jackson.kotlin)
    api(libs.square.okhttp)
    api(libs.square.okio)
    api(libs.rxjava3)

    implementation(libs.libsignal.client)
    implementation(libs.google.jsr305)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.rxjava3.kotlin)

    api(project(":vendor:signal:network-jvm"))
    implementation(project(":vendor:signal:util-jvm"))
    implementation(project(":vendor:signal:models-jvm"))

    testImplementation(libs.junit)
}
