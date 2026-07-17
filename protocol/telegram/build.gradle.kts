plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val tdlibVersion = libs.versions.tdlib.android.get()
val tdlibAar = file("libs/tdlib-core-${tdlibVersion}.aar")
if (!tdlibAar.exists()) {
    val fetch = rootProject.file("scripts/fetch-tdlib-prebuilt.sh")
    if (fetch.exists()) {
        logger.lifecycle("TDLib AAR missing — running scripts/fetch-tdlib-prebuilt.sh")
        val proc = ProcessBuilder("bash", fetch.absolutePath)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().use { logger.lifecycle(it.readText()) }
        check(proc.waitFor() == 0) { "fetch-tdlib-prebuilt.sh failed (exit ${proc.exitValue()})" }
    }
}
if (!tdlibAar.exists()) {
    throw GradleException(
        "Missing ${tdlibAar.path}. Run: ./scripts/fetch-tdlib-prebuilt.sh " +
            "(Maven ca.denisab85:tdlib has no libtdjni.so — refuse silent stub).",
    )
}

android {
    namespace = "ltechnologies.onionphone.securemessenger.protocol.telegram"
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    compileOnly(files(tdlibAar))
    testImplementation(files(tdlibAar))
    implementation(project(":protocol:api"))
    implementation(project(":core:proxy"))
    implementation(project(":core:network"))
    implementation(project(":data"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
