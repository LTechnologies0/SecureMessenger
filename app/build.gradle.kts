plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "ltechnologies.onionphone.securemessenger"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ltechnologies.onionphone.securemessenger"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.0.0-alpha.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val telegramApiId = localProperties.getProperty("telegram.api.id")
            ?: System.getenv("TELEGRAM_API_ID")
            ?: "0"
        val telegramApiHash = localProperties.getProperty("telegram.api.hash")
            ?: System.getenv("TELEGRAM_API_HASH")
            ?: ""
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                rootProject.file("gradle/privacy-logging.pro"),
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "**/libsignal_jni_*.dylib",
                "**/libsignal_jni_*.dll",
                "**/libsignal_jni_testing.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}
apply(from = rootProject.file("gradle/abi-release.gradle"))

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:proxy"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":data"))
    implementation(project(":protocol:api"))
    implementation(project(":protocol:xmpp"))
    implementation(project(":protocol:matrix"))
    implementation(project(":protocol:telegram"))
    val tdlibAar = rootProject.file(
        "protocol/telegram/libs/tdlib-core-${libs.versions.tdlib.android.get()}.aar",
    )
    check(tdlibAar.exists()) {
        "Missing ${tdlibAar.path} — run ./scripts/fetch-tdlib-prebuilt.sh before packaging"
    }
    implementation(files(tdlibAar))
    implementation(project(":protocol:signal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.webkit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    coreLibraryDesugaring(libs.android.desugar)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

apply(from = rootProject.file("gradle/release-signing.gradle"))
