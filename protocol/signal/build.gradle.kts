plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ltechnologies.onionphone.securemessenger.protocol.signal"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "**/libsignal_jni_testing.so",
            )
        }
        resources {
            excludes += setOf(
                "META-INF/versions/**/OSGI-INF/*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(project(":protocol:api"))
    implementation(project(":core:proxy"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":data"))
    implementation(project(":vendor:signal:libsignal-service"))
    implementation(project(":vendor:signal:models-jvm"))
    implementation(project(":vendor:signal:util-jvm"))

    implementation(libs.libsignal.client)
    implementation(libs.libsignal.android)
    implementation(libs.square.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
