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
    kotlin {
        javaInterop = true
    }
    sourcePath {
        srcDir("src/main/protowire")
    }
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.kotlin)
    api(libs.rxjava3)
    api(libs.square.okio)

    implementation(libs.google.jsr305)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.libsignal.client)
    implementation(project(":vendor:signal:util-jvm"))
    implementation(project(":vendor:signal:models-jvm"))

    testImplementation(libs.junit)
}
