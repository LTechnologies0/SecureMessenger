plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ltechnologies.onionphone.securemessenger.protocol.irc"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(project(":protocol:api"))
    implementation(project(":core:proxy"))
    implementation(project(":core:network"))
    implementation(project(":data"))
    implementation(libs.kitteh.irc)
    implementation(libs.netty.codec)
    implementation(libs.netty.handler)
    implementation(libs.netty.handler.proxy)
    // Kitteh / Netty APIs reference checker-qual annotations on types; keep them on classpath.
    compileOnly("org.checkerframework:checker-qual:3.18.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
