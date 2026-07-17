plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(libs.libsignal.client)
    implementation(libs.square.okio)
    implementation(project(":vendor:signal:util-jvm"))

    testImplementation(libs.junit)
}
