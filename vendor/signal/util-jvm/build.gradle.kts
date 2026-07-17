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
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.google.libphonenumber)
    implementation(libs.rxjava3)
    implementation(libs.rxjava3.kotlin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libsignal.client)

    testImplementation(libs.junit)
}
