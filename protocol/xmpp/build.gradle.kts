plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ltechnologies.onionphone.securemessenger.protocol.xmpp"
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
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":protocol:api"))
    implementation(project(":core:proxy"))
    implementation(project(":core:network"))
    implementation(project(":data"))
    implementation(libs.smack.android) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.smack.tcp) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.smack.extensions) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.smack.im) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.smack.omemo) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.smack.experimental) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
