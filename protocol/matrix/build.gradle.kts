plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ltechnologies.onionphone.securemessenger.protocol.matrix"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(platform(libs.trixnity.bom))
    implementation(project(":protocol:api"))
    implementation(project(":core:proxy"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":data"))
    implementation(libs.trixnity.client.jvm)
    implementation(libs.trixnity.client.media.okio.jvm)
    implementation(libs.trixnity.client.repository.exposed.jvm)
    implementation(libs.trixnity.crypto.jvm)
    implementation(libs.h2)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
